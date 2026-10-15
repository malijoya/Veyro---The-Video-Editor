package com.editor.veyro.data.project.file

import com.editor.veyro.core.model.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Portable project files (`*.veyro.json`) for backup, moving to another
 * device, or other tools:
 *
 * ```
 * { "format": "veyro.project", "formatVersion": 1, "exportedAtEpochMs": …,
 *   "project": { …the same versioned document the app stores… } }
 * ```
 *
 * The embedded document carries its own `schemaVersion`. Importing runs it
 * through the same migrations as stored projects, so older exports keep
 * working. Media is referenced by URI and fingerprint (name, size,
 * duration). On another device those URIs usually can't be opened, and the
 * project shows its media as missing rather than failing to import.
 */
public object ProjectInterchange {
    public const val FORMAT: String = "veyro.project"
    public const val FORMAT_VERSION: Int = 1
    public const val MIME_TYPE: String = "application/json"
    public const val FILE_SUFFIX: String = ".veyro.json"

    /** Imports larger than this are refused. Real projects are a few hundred KB at most. */
    public const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024

    public sealed interface ImportResult {
        public data class Ok(val project: Project) : ImportResult
        public data object NotAProjectFile : ImportResult
        public data object NewerVersion : ImportResult
        public data object Damaged : ImportResult
    }

    public fun export(project: Project, exportedAtEpochMs: Long): String {
        val element = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("exportedAtEpochMs", exportedAtEpochMs)
            put("project", ProjectDocumentCodec.encodeToElement(project))
        }
        return ProjectDocumentCodec.json.encodeToString(JsonObject.serializer(), element)
    }

    public fun import(text: String): ImportResult {
        val root = try {
            ProjectDocumentCodec.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ImportResult.NotAProjectFile
        }
        if (root["format"]?.jsonPrimitive?.content != FORMAT) return ImportResult.NotAProjectFile
        val formatVersion = runCatching { root["formatVersion"]?.jsonPrimitive?.int }.getOrNull() ?: return ImportResult.Damaged
        if (formatVersion > FORMAT_VERSION) return ImportResult.NewerVersion
        val document = runCatching { root["project"]?.jsonObject }.getOrNull() ?: return ImportResult.Damaged
        return when (val decoded = ProjectDocumentCodec.decode(document)) {
            is DecodeResult.Ok -> ImportResult.Ok(decoded.project)
            is DecodeResult.NewerSchema -> ImportResult.NewerVersion
            is DecodeResult.Corrupt -> ImportResult.Damaged
        }
    }

    /** A file name for [projectName]: unsafe characters removed, with [FILE_SUFFIX] appended. */
    public fun fileNameFor(projectName: String): String {
        val safe = projectName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifEmpty { "project" }
        return safe + FILE_SUFFIX
    }
}
