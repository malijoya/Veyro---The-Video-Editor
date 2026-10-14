package com.editor.veyro.media.importer

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Outcome of reading a picked file's metadata. */
public sealed interface ProbeResult {
    public data class Success(val media: MediaRef) : ProbeResult
    public data class Failure(val uri: String, val reason: ProbeFailure) : ProbeResult
}

public enum class ProbeFailure {
    /** Not video, image or audio, or a format the platform can't decode (such as SVG). */
    UNSUPPORTED_TYPE,

    /** The file no longer exists, or access was revoked. */
    NOT_ACCESSIBLE,

    /** The file opened but has no readable tracks or picture (corrupt, truncated, or an unknown codec). */
    UNREADABLE,
}

/**
 * Reads the metadata Veyro stores for imported media, without copying the file.
 *
 * - Video and audio: `MediaExtractor` supplies per-track formats (codec,
 *   microsecond duration, frame rate, sample rate, channels).
 *   `MediaMetadataRetriever` supplies rotation, frame count, bitrate and tags.
 *   [MediaMetadataAssembler] combines the two.
 * - Images: decodes bounds only, plus the EXIF orientation.
 *
 * Accepts `content://` URIs (pickers) and `file://` URIs (instrumented tests).
 * Each call checks for cancellation between the blocking steps.
 */
public class MediaProber(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val newId: () -> MediaId = { MediaId(UUID.randomUUID().toString()) },
) {
    private val resolver: ContentResolver get() = context.contentResolver

    public suspend fun probe(uri: Uri): ProbeResult = withContext(ioDispatcher) {
        try {
            // Fails fast for deleted files and revoked grants, before any decoder is created.
            resolver.openAssetFileDescriptor(uri, "r")?.close()
                ?: return@withContext ProbeResult.Failure(uri.toString(), ProbeFailure.NOT_ACCESSIBLE)
            val file = fileInfo(uri)
            val kind = MimeTypes.kindOf(file.mimeType)
                ?: return@withContext ProbeResult.Failure(uri.toString(), ProbeFailure.UNSUPPORTED_TYPE)
            ensureActive()
            when (kind) {
                MediaKind.IMAGE -> probeImage(uri, file)
                MediaKind.VIDEO, MediaKind.AUDIO -> {
                    val tracks = readTracks(uri)
                    ensureActive()
                    val retriever = readRetriever(uri)
                    if (tracks.isEmpty() && retriever == null) {
                        ProbeResult.Failure(uri.toString(), ProbeFailure.UNREADABLE)
                    } else {
                        MediaMetadataAssembler.assembleTimed(newId(), kind, file, tracks, retriever)
                    }
                }
            }
        } catch (e: SecurityException) {
            ProbeResult.Failure(uri.toString(), ProbeFailure.NOT_ACCESSIBLE)
        } catch (e: FileNotFoundException) {
            ProbeResult.Failure(uri.toString(), ProbeFailure.NOT_ACCESSIBLE)
        } catch (e: IOException) {
            ProbeResult.Failure(uri.toString(), ProbeFailure.UNREADABLE)
        }
    }

    private fun fileInfo(uri: Uri): RawFileInfo {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it) }?.let {
                name = it.name
                size = it.length()
            }
        } else {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        val fallbackName = name ?: uri.lastPathSegment
        val mime = resolver.getType(uri)
            ?: MimeTypes.fromFileName(fallbackName)
            ?: fallbackName?.substringAfterLast('.', "")?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return RawFileInfo(uri.toString(), name, mime, size)
    }

    private fun readTracks(uri: Uri): List<RawTrack> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                RawTrack(
                    mime = mime,
                    durationUs = format.long(MediaFormat.KEY_DURATION),
                    width = format.int(MediaFormat.KEY_WIDTH),
                    height = format.int(MediaFormat.KEY_HEIGHT),
                    rotationDegrees = format.int(MediaFormat.KEY_ROTATION),
                    frameRate = format.number(MediaFormat.KEY_FRAME_RATE)?.toDouble(),
                    sampleRateHz = format.int(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.int(MediaFormat.KEY_CHANNEL_COUNT),
                    bitrate = format.int(MediaFormat.KEY_BIT_RATE),
                )
            }
        } catch (e: IOException) {
            emptyList() // Container not supported by the extractor. The retriever may still read it.
        } catch (e: IllegalArgumentException) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun readRetriever(uri: Uri): RawRetrieverInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun string(key: Int): String? = retriever.extractMetadata(key)
            fun int(key: Int): Int? = string(key)?.trim()?.toIntOrNull()
            RawRetrieverInfo(
                durationMs = string(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()?.toLongOrNull(),
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotationDegrees = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                videoFrameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                } else {
                    null
                },
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                hasVideo = string(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                hasAudio = string(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                title = string(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = string(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = string(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        } catch (e: RuntimeException) {
            // setDataSource reports unsupported or corrupt files as IllegalArgumentException or RuntimeException.
            null
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                // release() declares IOException on API 29+. Nothing to recover.
            }
        }
    }

    private fun probeImage(uri: Uri, file: RawFileInfo): ProbeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return ProbeResult.Failure(uri.toString(), ProbeFailure.NOT_ACCESSIBLE)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Includes HEIC/AVIF on devices whose decoder doesn't support them.
            return ProbeResult.Failure(uri.toString(), ProbeFailure.UNREADABLE)
        }
        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: IOException) {
            0 // No or broken EXIF: treat as upright.
        }
        return ProbeResult.Success(
            MediaRef(
                id = newId(),
                uri = file.uri,
                kind = MediaKind.IMAGE,
                displayName = file.displayName,
                mimeType = file.mimeType ?: bounds.outMimeType,
                durationUs = 0,
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                rotationDegrees = MediaRef.normalizeRotation(rotation),
                sizeBytes = file.sizeBytes,
            ),
        )
    }
}

private fun MediaFormat.int(key: String): Int? = number(key)?.toInt()

private fun MediaFormat.long(key: String): Long? = if (containsKey(key)) {
    try {
        getLong(key)
    } catch (e: ClassCastException) {
        number(key)?.toLong()
    }
} else {
    null
}

/** Reads a numeric key whatever boxed type the container used (Integer, Long or Float). */
private fun MediaFormat.number(key: String): Number? {
    if (!containsKey(key)) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return getNumber(key)
    return try {
        getInteger(key)
    } catch (e: ClassCastException) {
        try {
            getFloat(key)
        } catch (e: ClassCastException) {
            try {
                getLong(key)
            } catch (e: ClassCastException) {
                null
            }
        }
    }
}
