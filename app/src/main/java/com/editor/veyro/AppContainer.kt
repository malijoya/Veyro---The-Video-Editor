package com.editor.veyro

import android.content.Context
import android.util.Log
import com.editor.veyro.data.project.ProjectAutosave
import com.editor.veyro.data.project.ProjectFileGateway
import com.editor.veyro.data.project.ProjectRepository
import com.editor.veyro.data.project.SourceReferencePolicy
import com.editor.veyro.data.project.file.FileProjectRepository
import com.editor.veyro.data.project.index.RoomProjectIndex
import com.editor.veyro.media.importer.MediaAccess
import com.editor.veyro.media.importer.MediaProber
import com.editor.veyro.media.export.ExportPipeline
import com.editor.veyro.media.export.Exports
import com.editor.veyro.media.importer.ThumbnailLoader
import com.editor.veyro.media.importer.WaveformLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring: one instance of each app-wide service. Kept
 * deliberately small. A DI framework will be considered once the graph grows.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val io = Dispatchers.IO

    /** Work that must outlive any screen, such as finishing an autosave after the editor closes. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val mediaAccess: MediaAccess = MediaAccess(appContext, io)
    val mediaProber: MediaProber = MediaProber(appContext, io)
    val thumbnails: ThumbnailLoader = ThumbnailLoader(appContext, io)

    /** Measured audio peaks, cached in memory and on disk. Shared by the editor (drawing, preview mix) and export. */
    val waveforms: WaveformLoader = WaveformLoader(appContext, io)

    /** Folders the system may clear at any time. Projects must never reference files inside them. */
    val sourcePolicy: SourceReferencePolicy = SourceReferencePolicy(
        listOfNotNull(appContext.cacheDir, appContext.externalCacheDir, appContext.codeCacheDir),
    )

    val projects: ProjectRepository = FileProjectRepository(
        rootDir = File(appContext.filesDir, "projects"),
        ioDispatcher = io,
        mediaAccessReleaser = mediaAccess,
        onUnreadable = { id, reason -> Log.w(TAG, "Skipping project $id: $reason") },
        index = RoomProjectIndex.create(appContext),
        sourcePolicy = sourcePolicy,
        onIndexError = { Log.w(TAG, "Project index update failed; it will be rebuilt from documents", it) },
        onRecovered = { id, file -> Log.w(TAG, "Project $id was recovered from $file") },
    )

    val autosave: ProjectAutosave = ProjectAutosave(projects, appScope)

    val projectFiles: ProjectFileGateway = ProjectFileGateway(appContext, io)

    /** Renders, verifies and saves exported videos. Each export screen runs its own. */
    fun exportPipeline(): ExportPipeline = Exports.pipeline(appContext)

    init {
        // No export can be running yet, so anything in the export work folder is left over from a killed process.
        Exports.deleteLeftovers(appContext)
    }

    val versionName: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val TAG = "Veyro"
    }
}
