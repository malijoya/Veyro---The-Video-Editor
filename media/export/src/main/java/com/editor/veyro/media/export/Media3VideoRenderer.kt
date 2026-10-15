package com.editor.veyro.media.export

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.media.render.CompositionFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders an [ExportPlan] with Media3 Transformer to an H.264/AAC MP4.
 *
 * - Transformer must be used from one thread with a Looper, and calls its
 *   listener on that thread. Everything here runs on the main thread; the
 *   heavy work happens on Transformer's own threads.
 * - Transformer writes to a file path only, so the pipeline renders to a
 *   private temporary file and copies it to the destination afterwards.
 * - Progress is Transformer's own percentage (`getProgress`), polled every
 *   [PROGRESS_INTERVAL_MS]. When it reports no progress, null is passed on.
 * - Cancelling the coroutine cancels Transformer, which frees the codecs,
 *   and deletes the partial file.
 */
@OptIn(UnstableApi::class)
public class Media3VideoRenderer(context: Context) : VideoRenderer {

    private val appContext = context.applicationContext

    override suspend fun render(plan: ExportPlan, output: File, onProgress: (Int?) -> Unit): RenderOutcome =
        withContext(Dispatchers.Main.immediate) {
            output.delete()
            val composition = try {
                CompositionFactory.forExport(plan, appContext)
            } catch (e: IllegalArgumentException) {
                return@withContext RenderOutcome.Failed(ExportError.PROCESSING_FAILED, "Invalid composition: ${e.message}")
            } catch (e: IllegalStateException) {
                return@withContext RenderOutcome.Failed(ExportError.PROCESSING_FAILED, "Invalid composition: ${e.message}")
            }

            val finished = CompletableDeferred<RenderOutcome>()
            val transformer = Transformer.Builder(appContext)
                // H.264 and AAC play almost everywhere. Transformer falls back if the device can't encode them.
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(RenderOutcome.Finished)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished.complete(RenderOutcome.Failed(errorFor(exportException), describe(exportException)))
                    }
                })
                .build()

            try {
                coroutineScope {
                    transformer.start(composition, output.absolutePath)
                    val poller = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            val state = transformer.getProgress(holder)
                            onProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                            delay(PROGRESS_INTERVAL_MS)
                        }
                    }
                    val outcome = finished.await()
                    poller.cancel()
                    if (outcome is RenderOutcome.Finished) onProgress(100)
                    outcome
                }.also { if (it is RenderOutcome.Failed) output.delete() }
            } catch (e: CancellationException) {
                // Still on the main thread: Transformer requires cancel() from the thread that started it.
                transformer.cancel()
                output.delete()
                throw e
            } catch (e: IllegalStateException) {
                transformer.cancel()
                output.delete()
                RenderOutcome.Failed(ExportError.UNKNOWN, e.message ?: "Transformer refused to start")
            }
        }

    internal companion object {
        const val PROGRESS_INTERVAL_MS = 250L

        fun errorFor(e: ExportException): ExportError = when (e.errorCode) {
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION,
            ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ExportException.ERROR_CODE_IO_UNSPECIFIED,
            -> if (isNoSpace(e)) ExportError.STORAGE_FULL else ExportError.SOURCE_UNREADABLE
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> ExportError.DECODER_UNSUPPORTED
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            -> ExportError.ENCODER_UNSUPPORTED
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
            -> ExportError.PROCESSING_FAILED
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_APPEND,
            -> if (isNoSpace(e)) ExportError.STORAGE_FULL else ExportError.OUTPUT_WRITE_FAILED
            else -> ExportError.UNKNOWN
        }

        private fun describe(e: ExportException): String =
            listOfNotNull(e.errorCodeName, e.message, e.cause?.message).joinToString(": ")

        private fun isNoSpace(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space left") == true }
    }
}
