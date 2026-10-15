package com.editor.veyro.data.project

import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * What project lists show, without opening the project document.
 * [mediaUris] lets the app tell which source files any project still uses.
 */
public data class ProjectSummary(
    val id: ProjectId,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val mediaCount: Int,
    val videoDurationUs: Long,
    val timelineDurationUs: Long,
    val coverUri: String?,
    val coverKind: MediaKind?,
    val hasImages: Boolean,
    val mediaUris: Set<String>,
) {
    public companion object {
        public fun of(project: Project): ProjectSummary = ProjectSummary(
            id = project.id,
            name = project.name,
            createdAtEpochMs = project.createdAtEpochMs,
            updatedAtEpochMs = project.updatedAtEpochMs,
            mediaCount = project.media.size,
            videoDurationUs = project.mediaDurationUs,
            timelineDurationUs = DefaultArrangement.timelineFor(project).durationUs,
            coverUri = project.coverMedia?.uri,
            coverKind = project.coverMedia?.kind,
            hasImages = project.media.any { it.kind == MediaKind.IMAGE },
            mediaUris = project.media.mapTo(linkedSetOf()) { it.uri },
        )
    }
}

/**
 * Searchable metadata about saved projects (the Room table in the app).
 *
 * The JSON documents remain the source of truth. The index is derived from
 * them and can always be rebuilt by reading the documents. The repository
 * reconciles it at startup.
 */
public interface ProjectIndex {
    /** All indexed projects, most recently updated first. */
    public val summaries: Flow<List<ProjectSummary>>

    public suspend fun upsert(summary: ProjectSummary)

    public suspend fun delete(id: ProjectId)

    public suspend fun ids(): Set<ProjectId>

    public suspend fun referencedMediaUris(): Set<String>
}

/** In-memory index for tests and for running without a database. */
public class InMemoryProjectIndex : ProjectIndex {
    private val rows = MutableStateFlow<Map<ProjectId, ProjectSummary>>(emptyMap())

    override val summaries: Flow<List<ProjectSummary>> =
        rows.map { all -> all.values.sortedWith(compareByDescending<ProjectSummary> { it.updatedAtEpochMs }.thenBy { it.id.value }) }

    override suspend fun upsert(summary: ProjectSummary) {
        rows.value = rows.value + (summary.id to summary)
    }

    override suspend fun delete(id: ProjectId) {
        rows.value = rows.value - id
    }

    override suspend fun ids(): Set<ProjectId> = rows.value.keys

    override suspend fun referencedMediaUris(): Set<String> = rows.value.values.flatMapTo(mutableSetOf()) { it.mediaUris }
}
