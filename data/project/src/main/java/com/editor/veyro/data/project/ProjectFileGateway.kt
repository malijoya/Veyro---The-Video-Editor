package com.editor.veyro.data.project

import android.content.Context
import android.net.Uri
import com.editor.veyro.data.project.file.ProjectInterchange
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads and writes user-chosen project files through the Storage Access
 * Framework. Takes the URIs returned by `CreateDocument` and `OpenDocument`.
 */
public class ProjectFileGateway(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val resolver = context.applicationContext.contentResolver

    /** @throws IOException if the file can't be written. */
    public suspend fun writeText(uri: String, text: String): Unit = withContext(ioDispatcher) {
        // "wt" truncates. Plain "w" may leave old bytes at the end of a file that was longer before.
        val stream = resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Can't open $uri for writing")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** @throws IOException if the file can't be read or is larger than [ProjectInterchange.MAX_FILE_BYTES]. */
    public suspend fun readText(uri: String): String = withContext(ioDispatcher) {
        val stream = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Can't open $uri")
        stream.use { input ->
            val bytes = input.readNBytesCompat(ProjectInterchange.MAX_FILE_BYTES + 1)
            if (bytes.size > ProjectInterchange.MAX_FILE_BYTES) throw IOException("File is too large to be a project")
            String(bytes, Charsets.UTF_8)
        }
    }

    /** Reads up to [limit] bytes. InputStream.readNBytes needs API 33, and minSdk is 24. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
