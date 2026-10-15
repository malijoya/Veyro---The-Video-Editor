package com.editor.veyro.media.export

import android.content.Context
import java.io.File

/** Wires the Android pieces of the export pipeline together. */
public object Exports {

    /** Temporary renders live here until they are verified and copied out. */
    public fun workDir(context: Context): File = File(context.applicationContext.cacheDir, "export")

    public fun pipeline(context: Context): ExportPipeline = ExportPipeline(
        renderer = Media3VideoRenderer(context),
        verifier = AndroidOutputVerifier(),
        publisher = AndroidExportPublisher(context),
        workDir = workDir(context),
    )

    /**
     * Deletes renders left behind by a process that died mid-export. Call it
     * once at startup, before any export can be running.
     */
    public fun deleteLeftovers(context: Context) {
        workDir(context).listFiles()?.forEach { it.delete() }
    }
}
