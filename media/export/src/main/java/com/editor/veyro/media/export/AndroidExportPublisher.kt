package com.editor.veyro.media.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a verified export to where the user asked.
 *
 * - [ExportDestination.Gallery]: a new entry in Movies/Veyro through
 *   MediaStore (Android 10+, no storage permission needed). It stays hidden
 *   (`IS_PENDING`) until the copy is complete, and is deleted if the copy
 *   fails.
 * - [ExportDestination.Document]: overwrites the document the user created
 *   with the system file picker. If the copy fails, the document is deleted
 *   so no truncated video is left behind.
 */
public class AndroidExportPublisher(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportPublisher {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun publish(file: File, destination: ExportDestination, displayName: String): PublishedFile =
        withContext(ioDispatcher) {
            when (destination) {
                ExportDestination.Gallery -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("Saving to the gallery needs Android 10")
                    // MediaStore renames on a name clash, so read back the name it used.
                    val uri = publishToGallery(file, displayName)
                    PublishedFile(uri, nameOf(Uri.parse(uri)) ?: displayName)
                }
                is ExportDestination.Document -> {
                    val uri = Uri.parse(destination.uri)
                    PublishedFile(publishToDocument(file, uri), nameOf(uri) ?: displayName)
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToGallery(file: File, displayName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MP4_MIME)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$GALLERY_FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused a new video")
        try {
            copy(file, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri.toString()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun publishToDocument(file: File, uri: Uri): String {
        try {
            copy(file, uri)
            return uri.toString()
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
    }

    private fun nameOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun copy(file: File, uri: Uri) {
        // "w" alone may not truncate on some providers; "wt" does where supported.
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException("Can't open $uri for writing")
        stream.use { out -> file.inputStream().use { it.copyTo(out, BUFFER_BYTES) } }
    }

    public companion object {
        public const val MP4_MIME: String = "video/mp4"
        public const val GALLERY_FOLDER: String = "Veyro"
        private const val BUFFER_BYTES = 1 shl 16

        /** Whether [ExportDestination.Gallery] works on this device. */
        public val galleryAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
