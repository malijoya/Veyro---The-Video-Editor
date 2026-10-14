package com.editor.veyro.media.importer

import com.editor.veyro.core.model.project.MediaKind

/** MIME handling for imports. Pure Kotlin, so it is unit-tested on the host. */
public object MimeTypes {

    /** Image types the platform can't decode to a bitmap, so they can't be used as clips. */
    private val UNSUPPORTED_IMAGES = setOf("image/svg+xml", "image/vnd.adobe.photoshop", "image/x-icon", "image/tiff")

    /** Containers that hold audio but report a non-audio top-level type. */
    private val AUDIO_APPLICATION_TYPES = setOf("application/ogg")

    /**
     * Which kind of media a MIME type is, or `null` if Veyro can't import it.
     * Video containers that turn out to hold only audio (such as some `.mp4`
     * voice memos) are reclassified after probing, not here.
     */
    public fun kindOf(mimeType: String?): MediaKind? {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime in AUDIO_APPLICATION_TYPES -> MediaKind.AUDIO
            mime.startsWith("image/") && mime !in UNSUPPORTED_IMAGES -> MediaKind.IMAGE
            else -> null
        }
    }

    private val BY_EXTENSION = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2", "webm" to "video/webm", "mkv" to "video/x-matroska", "ts" to "video/mp2t",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/x-wav",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac",
        "amr" to "audio/amr", "mka" to "audio/x-matroska",
    )

    /**
     * Guesses a MIME type from a file name's extension. Used when a provider
     * reports no type (common with `file://` and some cloud providers).
     */
    public fun fromFileName(name: String?): String? {
        val extension = name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return if (extension.isNullOrEmpty()) null else BY_EXTENSION[extension]
    }
}
