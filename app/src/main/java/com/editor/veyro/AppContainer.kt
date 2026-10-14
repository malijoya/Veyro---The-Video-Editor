package com.editor.veyro

import android.content.Context
import android.util.Log
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.data.project.file.FileProjectRepository
import com.editor.veyro.media.importer.MediaAccess
import com.editor.veyro.media.importer.MediaProber
import com.editor.veyro.media.importer.ThumbnailLoader
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency wiring: one instance of each app-wide service. Kept
 * deliberately small. A DI framework will be considered once the graph grows
 * (and once KSP compatibility with the current Kotlin version is confirmed).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val io = Dispatchers.IO

    val mediaAccess: MediaAccess = MediaAccess(appContext, io)
    val mediaProber: MediaProber = MediaProber(appContext, io)
    val thumbnails: ThumbnailLoader = ThumbnailLoader(appContext, io)

    val projects: ProjectRepository = FileProjectRepository(
        rootDir = File(appContext.filesDir, "projects"),
        ioDispatcher = io,
        mediaAccessReleaser = mediaAccess,
        onUnreadable = { file, reason -> Log.w(TAG, "Skipping project file $file: $reason") },
    )

    val versionName: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val TAG = "Veyro"
    }
}
