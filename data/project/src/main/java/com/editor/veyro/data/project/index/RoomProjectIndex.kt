package com.editor.veyro.data.project.index

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.data.project.ProjectIndex
import com.editor.veyro.data.project.ProjectSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One row per project: list metadata only. The full project lives in its JSON document. */
@Entity(tableName = "projects", indices = [Index("updatedAtEpochMs")])
internal data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val mediaCount: Int,
    val videoDurationUs: Long,
    val timelineDurationUs: Long,
    val coverUri: String?,
    /** [MediaKind] name, stored as text so enum reordering can't corrupt rows. */
    val coverKind: String?,
    val hasImages: Boolean,
)

/** Source URIs used by each project. Deleting a project removes its rows. */
@Entity(
    tableName = "project_media",
    primaryKeys = ["projectId", "uri"],
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("uri")],
)
internal data class ProjectMediaEntity(val projectId: String, val uri: String)

@Dao
internal interface ProjectIndexDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC, id ASC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project_media")
    fun observeMedia(): Flow<List<ProjectMediaEntity>>

    @Upsert
    suspend fun upsertProject(project: ProjectEntity)

    @Query("DELETE FROM project_media WHERE projectId = :projectId")
    suspend fun deleteMediaOf(projectId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMedia(media: List<ProjectMediaEntity>)

    @Transaction
    suspend fun replace(project: ProjectEntity, media: List<ProjectMediaEntity>) {
        upsertProject(project)
        deleteMediaOf(project.id)
        insertMedia(media)
    }

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT id FROM projects")
    suspend fun ids(): List<String>

    @Query("SELECT DISTINCT uri FROM project_media")
    suspend fun referencedUris(): List<String>
}

/**
 * The project index database.
 *
 * Version 1. Future schema changes must add a Room migration, plus a test
 * against the exported schema in `data/project/schemas`. Because the index
 * is derived data, a failed migration may fall back to rebuilding from the
 * documents, and nothing is lost.
 */
@Database(entities = [ProjectEntity::class, ProjectMediaEntity::class], version = 1, exportSchema = true)
internal abstract class ProjectIndexDatabase : RoomDatabase() {
    abstract fun dao(): ProjectIndexDao
}

/** [ProjectIndex] backed by Room. */
public class RoomProjectIndex internal constructor(private val dao: ProjectIndexDao) : ProjectIndex {

    override val summaries: Flow<List<ProjectSummary>> =
        combine(dao.observeProjects(), dao.observeMedia()) { projects, media ->
            val urisByProject = media.groupBy({ it.projectId }, { it.uri })
            projects.mapNotNull { it.toSummary(urisByProject[it.id].orEmpty()) }
        }

    override suspend fun upsert(summary: ProjectSummary) {
        dao.replace(
            ProjectEntity(
                id = summary.id.value,
                name = summary.name,
                createdAtEpochMs = summary.createdAtEpochMs,
                updatedAtEpochMs = summary.updatedAtEpochMs,
                mediaCount = summary.mediaCount,
                videoDurationUs = summary.videoDurationUs,
                timelineDurationUs = summary.timelineDurationUs,
                coverUri = summary.coverUri,
                coverKind = summary.coverKind?.name,
                hasImages = summary.hasImages,
            ),
            summary.mediaUris.map { ProjectMediaEntity(summary.id.value, it) },
        )
    }

    override suspend fun delete(id: ProjectId) {
        dao.delete(id.value)
    }

    override suspend fun ids(): Set<ProjectId> = dao.ids().mapNotNullTo(mutableSetOf()) { runCatching { ProjectId(it) }.getOrNull() }

    override suspend fun referencedMediaUris(): Set<String> = dao.referencedUris().toSet()

    private fun ProjectEntity.toSummary(uris: List<String>): ProjectSummary? = runCatching {
        ProjectSummary(
            id = ProjectId(id),
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            mediaCount = mediaCount,
            videoDurationUs = videoDurationUs,
            timelineDurationUs = timelineDurationUs,
            coverUri = coverUri,
            coverKind = coverKind?.let { name -> MediaKind.entries.firstOrNull { it.name == name } },
            hasImages = hasImages,
            mediaUris = uris.toSet(),
        )
    }.getOrNull()

    public companion object {
        private const val DATABASE_NAME = "project-index.db"

        public fun create(context: Context): RoomProjectIndex {
            val database = Room.databaseBuilder(context.applicationContext, ProjectIndexDatabase::class.java, DATABASE_NAME)
                // The index is derived from the JSON documents. If a future version can't migrate it, rebuilding loses nothing.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            return RoomProjectIndex(database.dao())
        }

        /** An in-memory database, for instrumented tests. */
        public fun inMemory(context: Context): RoomProjectIndex =
            RoomProjectIndex(Room.inMemoryDatabaseBuilder(context, ProjectIndexDatabase::class.java).build().dao())
    }
}
