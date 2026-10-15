package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Outcome of reading one project's document. */
internal sealed interface StoreRead {
    /** [recoveredFrom] names the fallback file used when the main document was missing or damaged. */
    data class Loaded(val project: Project, val recoveredFrom: String?) : StoreRead
    data object Missing : StoreRead
    data class Unreadable(val reason: String) : StoreRead
}

/**
 * Project documents on disk, one folder per project:
 * - `project.json`: the current document
 * - `project.json.bak`: the previous version, kept as a fallback
 * - `project.json.tmp`: only exists briefly while saving
 *
 * Safe writes. Each save:
 * 1. writes the new document to `.tmp` and syncs it to storage
 * 2. renames the current document to `.bak`
 * 3. renames `.tmp` to `project.json`
 *
 * A crash at any point leaves a complete document:
 * - during step 1, the old main file is untouched
 * - between steps 2 and 3, the synced `.tmp` is complete
 *
 * Reads prefer the main file, then a complete `.tmp` if the main file is
 * missing, then `.bak` if neither decodes.
 *
 * Plain JVM code, unit-tested on the host.
 */
internal class ProjectDocumentStore(private val rootDir: File) {

    fun ids(): List<ProjectId> =
        rootDir.listFiles { file -> file.isDirectory }.orEmpty()
            .filter { dir -> CANDIDATES.any { File(dir, it).isFile } }
            .mapNotNull { dir -> runCatching { ProjectId(dir.name) }.getOrNull() }

    fun read(id: ProjectId): StoreRead {
        val dir = dirOf(id)
        val main = File(dir, DOCUMENT)
        val candidates = buildList {
            add(main)
            if (!main.exists()) add(File(dir, TEMP))
            add(File(dir, BACKUP))
        }.filter { it.isFile }
        if (candidates.isEmpty()) return StoreRead.Missing

        val problems = mutableListOf<String>()
        for (file in candidates) {
            val text = try {
                file.readText()
            } catch (e: IOException) {
                problems += "${file.name}: I/O error ${e.message}"
                continue
            }
            when (val decoded = ProjectDocumentCodec.decode(text)) {
                is DecodeResult.Ok -> {
                    if (decoded.project.id != id) {
                        problems += "${file.name}: ID ${decoded.project.id.value} does not match folder"
                        continue
                    }
                    return StoreRead.Loaded(decoded.project, recoveredFrom = file.name.takeIf { file != main })
                }
                // A newer app wrote this. Don't fall back to an older copy and risk overwriting newer work later.
                is DecodeResult.NewerSchema -> return StoreRead.Unreadable("Saved by a newer app version (schema ${decoded.version})")
                is DecodeResult.Corrupt -> problems += "${file.name}: corrupt (${decoded.cause.message})"
            }
        }
        return StoreRead.Unreadable(problems.joinToString("; "))
    }

    fun write(project: Project) {
        val dir = dirOf(project.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create $dir")
        val main = File(dir, DOCUMENT)
        val temp = File(dir, TEMP)
        val backup = File(dir, BACKUP)

        FileOutputStream(temp).use { out ->
            out.write(ProjectDocumentCodec.encode(project).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (main.exists()) replace(main, backup)
        replace(temp, main)
    }

    fun delete(id: ProjectId) {
        val dir = dirOf(id)
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not delete $dir")
    }

    fun sizeBytes(): Long = rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun dirOf(id: ProjectId) = File(rootDir, id.value)

    /**
     * Renames [from] over [to]. On Android (Linux), rename(2) replaces
     * atomically. On Windows, used only for host unit tests, it can't replace
     * an existing file, so [to] is deleted first. That path is never reached
     * on a device.
     */
    private fun replace(from: File, to: File) {
        if (!from.renameTo(to) && !(to.delete() && from.renameTo(to))) {
            throw IOException("Could not move ${from.name} to ${to.name}")
        }
    }

    companion object {
        const val DOCUMENT = "project.json"
        const val BACKUP = "project.json.bak"
        const val TEMP = "project.json.tmp"
        private val CANDIDATES = listOf(DOCUMENT, TEMP, BACKUP)
    }
}
