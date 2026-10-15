package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.project.ProjectName
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.data.project.MediaAccessReleaser
import com.editor.veyro.data.project.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stores each project as `<rootDir>/<projectId>/project.json`.
 *
 * - The JSON documents are the source of truth. An in-memory map is loaded
 *   lazily and kept in step with every write.
 * - Writes are atomic: data goes to a temp file, is synced, then renamed over
 *   the old document. A crash never leaves a half-written project.
 * - Unreadable or newer-schema documents are skipped and reported to
 *   [onUnreadable]. They are never deleted.
 *
 * Plain JVM code (no Android APIs), so it is unit-tested on the host.
 */
public class FileProjectRepository(
    private val rootDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val mediaAccessReleaser: MediaAccessReleaser = MediaAccessReleaser { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onUnreadable: (file: File, reason: String) -> Unit = { _, _ -> },
) : ProjectRepository {

    private val mutex = Mutex()
    private val cache = MutableStateFlow<Map<ProjectId, Project>?>(null)

    override val projects: Flow<List<Project>> = flow {
        ensureLoaded()
        emitAll(
            cache.filterNotNull().map { all ->
                all.values.sortedWith(compareByDescending<Project> { it.updatedAtEpochMs }.thenBy { it.id.value })
            },
        )
    }.distinctUntilChanged()

    override fun project(id: ProjectId): Flow<Project?> = flow {
        ensureLoaded()
        emitAll(cache.filterNotNull().map { it[id] })
    }.distinctUntilChanged()

    override suspend fun create(name: String, media: List<MediaRef>): Project {
        val normalized = requireNotNull(ProjectName.normalize(name)) { "Project name must not be blank" }
        require(media.isNotEmpty()) { "A project needs at least one media item" }
        val now = clock()
        val project = Project(
            id = ProjectId(newId()),
            name = normalized,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            media = media,
        )
        mutate { current ->
            write(project)
            current + (project.id to project)
        }
        return project
    }

    override suspend fun addMedia(id: ProjectId, media: List<MediaRef>): Int? {
        var added: Int? = null
        mutate { current ->
            val existing = current[id] ?: return@mutate current
            val knownUris = existing.media.mapTo(mutableSetOf()) { it.uri }
            val fresh = media.filter { knownUris.add(it.uri) }
            added = fresh.size
            if (fresh.isEmpty()) return@mutate current
            val updated = existing.copy(
                media = existing.media + fresh,
                timeline = existing.timeline?.let { DefaultArrangement.append(it, fresh) },
                updatedAtEpochMs = clock(),
            )
            write(updated)
            current + (id to updated)
        }
        return added
    }

    override suspend fun updateTimeline(id: ProjectId, timeline: Timeline): Boolean {
        var found = false
        mutate { current ->
            val existing = current[id] ?: return@mutate current
            found = true
            val mediaIds = existing.media.map { it.id }.toSet()
            timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    require(clip.mediaId in mediaIds) { "Clip ${clip.id.value} references media not in the project" }
                }
            }
            if (existing.timeline == timeline) return@mutate current
            val updated = existing.copy(timeline = timeline, updatedAtEpochMs = clock())
            write(updated)
            current + (id to updated)
        }
        return found
    }

    override suspend fun rename(id: ProjectId, name: String): Boolean {
        val normalized = requireNotNull(ProjectName.normalize(name)) { "Project name must not be blank" }
        var found = false
        mutate { current ->
            val existing = current[id] ?: return@mutate current
            found = true
            if (existing.name == normalized) return@mutate current
            val renamed = existing.copy(name = normalized, updatedAtEpochMs = clock())
            write(renamed)
            current + (id to renamed)
        }
        return found
    }

    override suspend fun delete(id: ProjectId): Boolean {
        var found = false
        mutate { current ->
            if (id !in current) return@mutate current
            found = true
            val dir = projectDir(id)
            if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not delete $dir")
            current - id
        }
        if (found) mediaAccessReleaser.releaseAllExcept(referencedMediaUris())
        return found
    }

    override suspend fun referencedMediaUris(): Set<String> {
        ensureLoaded()
        return cache.value.orEmpty().values.flatMapTo(mutableSetOf()) { p -> p.media.map { it.uri } }
    }

    override suspend fun storageBytes(): Long = withContext(ioDispatcher) {
        rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private suspend fun ensureLoaded() {
        if (cache.value != null) return
        mutex.withLock {
            if (cache.value != null) return
            cache.value = withContext(ioDispatcher) { loadAll() }
        }
    }

    private suspend fun mutate(block: (Map<ProjectId, Project>) -> Map<ProjectId, Project>) {
        ensureLoaded()
        mutex.withLock {
            val current = checkNotNull(cache.value)
            val updated = withContext(ioDispatcher) { block(current) }
            if (updated !== current) cache.value = updated
        }
    }

    private fun loadAll(): Map<ProjectId, Project> {
        val dirs = rootDir.listFiles { file -> file.isDirectory }.orEmpty()
        val result = mutableMapOf<ProjectId, Project>()
        for (dir in dirs) {
            val file = File(dir, DOCUMENT_NAME)
            if (!file.isFile) continue
            val text = try {
                file.readText()
            } catch (e: IOException) {
                onUnreadable(file, "I/O error: ${e.message}")
                continue
            }
            when (val decoded = ProjectDocumentCodec.decode(text)) {
                is DecodeResult.Ok -> {
                    if (decoded.project.id.value != dir.name) {
                        onUnreadable(file, "ID ${decoded.project.id.value} does not match folder")
                    } else {
                        result[decoded.project.id] = decoded.project
                    }
                }
                is DecodeResult.NewerSchema ->
                    onUnreadable(file, "Saved by a newer app version (schema ${decoded.version})")
                is DecodeResult.Corrupt -> onUnreadable(file, "Corrupt: ${decoded.cause.message}")
            }
        }
        return result
    }

    private fun write(project: Project) {
        val dir = projectDir(project.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create $dir")
        val target = File(dir, DOCUMENT_NAME)
        val temp = File(dir, "$DOCUMENT_NAME.tmp")
        FileOutputStream(temp).use { out ->
            out.write(ProjectDocumentCodec.encode(project).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // On Android (Linux), rename(2) atomically replaces the target. On Windows,
        // used only for host unit tests, rename can't replace an existing file,
        // so the fallback deletes it first. That path is never reached on a device.
        if (!temp.renameTo(target) && !(target.delete() && temp.renameTo(target))) {
            temp.delete()
            throw IOException("Could not replace $target")
        }
    }

    private fun projectDir(id: ProjectId): File = File(rootDir, id.value)

    private companion object {
        const val DOCUMENT_NAME = "project.json"
    }
}
