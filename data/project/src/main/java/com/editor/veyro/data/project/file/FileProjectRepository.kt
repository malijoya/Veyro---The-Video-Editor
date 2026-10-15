package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectName
import com.editor.veyro.core.model.project.ProjectSettings
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.data.project.InMemoryProjectIndex
import com.editor.veyro.data.project.MediaAccessReleaser
import com.editor.veyro.data.project.ProjectIndex
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.data.project.ProjectSummary
import com.editor.veyro.data.project.SourceReferencePolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Projects stored as JSON documents (see [ProjectDocumentStore]), with list
 * metadata in a [ProjectIndex] (Room in the app).
 *
 * - **Source of truth:** the documents. The index is updated after every
 *   write. On first use it is reconciled with the documents on disk: missing
 *   rows are added and stale rows removed. A lost or reset index rebuilds
 *   itself.
 * - **Lazy loading:** a document is opened only when its project is opened.
 *   Lists and "which media are in use" come from the index.
 * - **Unreadable documents:** documents that are unreadable, or from a newer
 *   app version, are reported to [onUnreadable] once and left untouched on
 *   disk.
 * - **Safe writes:** every write goes through the store's
 *   temp → sync → backup → rename sequence.
 * - **Source policy:** new media is checked against [sourcePolicy], so
 *   temporary cache paths never become permanent references.
 *
 * The file and index logic is plain JVM code, unit-tested on the host.
 */
public class FileProjectRepository(
    rootDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val mediaAccessReleaser: MediaAccessReleaser = MediaAccessReleaser { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onUnreadable: (projectId: String, reason: String) -> Unit = { _, _ -> },
    private val index: ProjectIndex = InMemoryProjectIndex(),
    private val sourcePolicy: SourceReferencePolicy = SourceReferencePolicy.PERMISSIVE,
    private val onIndexError: (Throwable) -> Unit = {},
    private val onRecovered: (projectId: String, fromFile: String) -> Unit = { _, _ -> },
) : ProjectRepository {

    private val store = ProjectDocumentStore(rootDir)
    private val mutex = Mutex()

    /** Opened projects. A null value means "looked up, but missing or unreadable", so it isn't re-read. */
    private val opened = MutableStateFlow<Map<ProjectId, Project?>>(emptyMap())

    @Volatile
    private var reconciled = false

    override val summaries: Flow<List<ProjectSummary>> = flow {
        reconcileIndex()
        emitAll(index.summaries)
    }

    /**
     * Every readable project, fully loaded. This opens every document, so
     * it's for tests and tooling. Screens use [summaries].
     */
    public val projects: Flow<List<Project>> = flow {
        reconcileIndex()
        val ids = withContext(ioDispatcher) { store.ids() }
        ids.forEach { open(it) }
        emitAll(
            opened.map { all ->
                all.values.filterNotNull().sortedWith(compareByDescending<Project> { it.updatedAtEpochMs }.thenBy { it.id.value })
            },
        )
    }.distinctUntilChanged()

    override fun project(id: ProjectId): Flow<Project?> = flow {
        open(id)
        emitAll(opened.map { it[id] })
    }.distinctUntilChanged()

    override suspend fun create(name: String, media: List<MediaRef>, settings: ProjectSettings?): Project {
        val normalized = requireNotNull(ProjectName.normalize(name)) { "Project name must not be blank" }
        require(media.isNotEmpty()) { "A project needs at least one media item" }
        sourcePolicy.requirePermanent(media)
        val now = clock()
        val project = Project(
            id = ProjectId(newId()),
            name = normalized,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            media = media,
            settings = settings ?: ProjectSettings.matching(media),
        )
        save(project)
        return project
    }

    override suspend fun addMedia(id: ProjectId, media: List<MediaRef>, arrange: Boolean): Int? {
        sourcePolicy.requirePermanent(media)
        var added: Int? = null
        modify(id) { existing ->
            val knownUris = existing.media.mapTo(mutableSetOf()) { it.uri }
            val fresh = media.filter { knownUris.add(it.uri) }
            added = fresh.size
            if (fresh.isEmpty()) {
                null
            } else {
                existing.copy(
                    media = existing.media + fresh,
                    timeline = if (arrange) {
                        existing.timeline?.let { DefaultArrangement.append(it, fresh, existing.settings.photoDurationUs) }
                    } else {
                        // Kept off the timeline. An unedited project gets its current arrangement stored first,
                        // or the default arrangement would place the new media on the main track.
                        existing.timeline ?: DefaultArrangement.timelineFor(existing)
                    },
                    updatedAtEpochMs = clock(),
                )
            }
        }
        return added
    }

    override suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean = modify(id) { existing ->
        val mediaIds = existing.media.map { it.id }.toSet()
        timeline.tracks.forEach { track ->
            track.clips.forEach { clip ->
                clip.mediaId?.let { id -> require(id in mediaIds) { "Clip ${clip.id.value} references media not in the project" } }
            }
        }
        // The project's settings own the frame grid. A timeline can't change it.
        val aligned = timeline.copy(frameRate = existing.settings.frameRate)
        if (existing.timeline == aligned) null else existing.copy(timeline = aligned, updatedAtEpochMs = clock())
    }

    override suspend fun updateSettings(id: ProjectId, settings: ProjectSettings): Boolean = modify(id) { existing ->
        if (existing.settings == settings) {
            null
        } else {
            existing.copy(
                settings = settings,
                timeline = existing.timeline?.copy(frameRate = settings.frameRate),
                updatedAtEpochMs = clock(),
            )
        }
    }

    override suspend fun importProject(project: Project): Project {
        val imported = project.copy(id = ProjectId(newId()), updatedAtEpochMs = clock())
        save(imported)
        return imported
    }

    override suspend fun rename(id: ProjectId, name: String): Boolean {
        val normalized = requireNotNull(ProjectName.normalize(name)) { "Project name must not be blank" }
        return modify(id) { existing ->
            if (existing.name == normalized) null else existing.copy(name = normalized, updatedAtEpochMs = clock())
        }
    }

    override suspend fun delete(id: ProjectId): Boolean {
        reconcileIndex()
        val existed = mutex.withLock {
            val known = withContext(ioDispatcher) { store.ids().contains(id) }
            if (known) {
                withContext(ioDispatcher) { store.delete(id) }
                opened.value = opened.value + (id to null)
            }
            indexSafely { index.delete(id) }
            known
        }
        if (existed) mediaAccessReleaser.releaseAllExcept(referencedMediaUris())
        return existed
    }

    override suspend fun referencedMediaUris(): Set<String> {
        reconcileIndex()
        return index.referencedMediaUris()
    }

    override suspend fun storageBytes(): Long = withContext(ioDispatcher) { store.sizeBytes() }

    // ---- Internals ----

    /** Loads [id] into [opened] once. Unreadable documents are reported once and remembered as null. */
    private suspend fun open(id: ProjectId) {
        if (id in opened.value) return
        mutex.withLock {
            if (id in opened.value) return
            val project = withContext(ioDispatcher) { readOrReport(id) }
            opened.value = opened.value + (id to project)
        }
    }

    private fun readOrReport(id: ProjectId): Project? = when (val read = store.read(id)) {
        is StoreRead.Loaded -> {
            read.recoveredFrom?.let { onRecovered(id.value, it) }
            read.project
        }
        StoreRead.Missing -> null
        is StoreRead.Unreadable -> {
            onUnreadable(id.value, read.reason)
            null
        }
    }

    /**
     * Applies [change] to the stored project. Returning null means "nothing
     * to save". Returns false if the project doesn't exist.
     */
    private suspend fun modify(id: ProjectId, change: (Project) -> Project?): Boolean {
        open(id)
        return mutex.withLock {
            val existing = opened.value[id] ?: return@withLock false
            val updated = change(existing) ?: return@withLock true
            withContext(ioDispatcher) { store.write(updated) }
            opened.value = opened.value + (id to updated)
            indexSafely { index.upsert(ProjectSummary.of(updated)) }
            true
        }
    }

    private suspend fun save(project: Project) {
        reconcileIndex()
        mutex.withLock {
            withContext(ioDispatcher) { store.write(project) }
            opened.value = opened.value + (project.id to project)
            indexSafely { index.upsert(ProjectSummary.of(project)) }
        }
    }

    /** Brings the index in line with the documents on disk, once per repository. */
    private suspend fun reconcileIndex() {
        if (reconciled) return
        val storeIds = withContext(ioDispatcher) { store.ids() }.toSet()
        val indexIds = indexSafely { index.ids() } ?: emptySet()
        for (id in storeIds - indexIds) {
            open(id)
            opened.value[id]?.let { project -> indexSafely { index.upsert(ProjectSummary.of(project)) } }
        }
        for (id in indexIds - storeIds) indexSafely { index.delete(id) }
        reconciled = true
    }

    /** Index failures never fail a save. The document is already written, and the next start reconciles. */
    private suspend fun <T> indexSafely(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        onIndexError(e)
        null
    }
}
