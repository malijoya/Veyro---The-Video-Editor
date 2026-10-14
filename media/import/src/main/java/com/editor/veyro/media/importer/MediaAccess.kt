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
