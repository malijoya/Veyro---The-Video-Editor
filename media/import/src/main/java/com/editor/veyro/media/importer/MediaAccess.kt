package com.editor.veyro.media.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.editor.veyro.data.project.MediaAccessReleaser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Manages persisted read access to picked media, so projects can reopen their
 * source files after the app restarts.
 *
 * The system photo picker and the document picker both grant persistable read
 * access. Some fallback pickers don't. For those, [persist] returns false and
 * the media works only until the grant expires (usually when the task ends).
 */
public class MediaAccess(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : MediaAccessReleaser {

    /** Number of URIs the app currently holds persisted read access to. */
    public fun persistedCount(): Int = context.contentResolver.persistedUriPermissions.count { it.isReadPermission }

    public suspend fun persist(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Whether [uri] can still be opened for reading. It returns false once
     * the file is deleted or moved, or once its grant is revoked. Opening
     * only a descriptor is cheap: nothing is read or decoded.
     */
    public suspend fun isAvailable(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: SecurityException) {
            false
        } catch (e: java.io.FileNotFoundException) {
            false
        } catch (e: IllegalArgumentException) {
            false // Malformed or unknown URI.
        }
    }

    /**
     * Checks a project's source file: [MediaHealth.MISSING] if it can't be
     * opened, and [MediaHealth.CHANGED] if its size no longer matches the size
     * recorded at import. The size is the provider's `OpenableColumns.SIZE`, or
     * the file length for `file://`. Unknown sizes count as unchanged.
     */
    public suspend fun health(media: com.editor.veyro.core.model.project.MediaRef): com.editor.veyro.core.model.project.MediaHealth =
        withContext(ioDispatcher) {
            val uri = Uri.parse(media.uri)
            if (!isAvailable(uri)) return@withContext com.editor.veyro.core.model.project.MediaHealth.MISSING
            val expected = media.sizeBytes ?: return@withContext com.editor.veyro.core.model.project.MediaHealth.AVAILABLE
            val actual = currentSize(uri) ?: return@withContext com.editor.veyro.core.model.project.MediaHealth.AVAILABLE
            if (actual == expected) {
                com.editor.veyro.core.model.project.MediaHealth.AVAILABLE
            } else {
                com.editor.veyro.core.model.project.MediaHealth.CHANGED
            }
        }

    private fun currentSize(uri: Uri): Long? = try {
        if (uri.scheme == android.content.ContentResolver.SCHEME_FILE) {
            uri.path?.let { java.io.File(it).length() }
        } else {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    override suspend fun releaseAllExcept(referencedUris: Set<String>): Unit = withContext(ioDispatcher) {
        val resolver = context.contentResolver
        resolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.toString() !in referencedUris }
            .forEach { permission ->
                try {
                    resolver.releasePersistableUriPermission(permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Already released by the system. Nothing to do.
                }
            }
    }
}
