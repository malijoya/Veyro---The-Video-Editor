package com.editor.veyro.media.export

import com.editor.veyro.core.timeline.export.ExportPlan
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** Why an export failed. The UI turns each into a message and a suggestion. */
public enum class ExportError {
    /** A source file couldn't be opened or read (deleted, permission revoked). */
    SOURCE_UNREADABLE,

    /** The device can't decode one of the sources. */
    DECODER_UNSUPPORTED,

    /** The device's encoder rejected the output format or size. A lower resolution may work. */
    ENCODER_UNSUPPORTED,

    /** Rendering frames or mixing audio failed. */
    PROCESSING_FAILED,

    /** Writing the MP4 failed. */
    OUTPUT_WRITE_FAILED,

    /** The renderer finished, but the file isn't a playable video of the expected length. */
    INVALID_OUTPUT,

    /** Not enough free space for the export. */
    STORAGE_FULL,

    /** The video was made but couldn't be saved to the chosen place. */
    SAVE_FAILED,

    UNKNOWN,
}

/** Where the finished video goes. */
public sealed interface ExportDestination {
    /** The shared Movies/Veyro folder, visible in gallery apps. Android 10 and later. */
    public data object Gallery : ExportDestination

    /** A document the user created with the system file picker. */
    public data class Document(val uri: String) : ExportDestination
}

/** The renderer's answer. Success only means it says it finished; [OutputVerifier] decides if the file is valid. */
public sealed interface RenderOutcome {
    public data object Finished : RenderOutcome
    public data class Failed(val error: ExportError, val detail: String) : RenderOutcome
}

/** Facts read back from the finished file, not from the request. */
public data class VerifiedVideo(
    val durationUs: Long,
    /** Upright size, after applying the file's rotation. */
    val widthPx: Int,
    val heightPx: Int,
    val videoMime: String,
    val audioMime: String?,
    val sizeBytes: Long,
)

public sealed interface VerifyOutcome {
    public data class Valid(val video: VerifiedVideo) : VerifyOutcome
    public data class Invalid(val detail: String) : VerifyOutcome
}

/** A video saved where the user asked. */
public data class ExportedVideo(
    val uri: String,
    val displayName: String,
    val destination: ExportDestination,
    val video: VerifiedVideo,
)

/** Renders a plan to an MP4 file. Implementations must stop and clean up when the coroutine is cancelled. */
public fun interface VideoRenderer {
    /**
     * @param onProgress the renderer's own completion percentage (0..100),
     *   or null when it can't tell. Never estimated.
     */
    public suspend fun render(plan: ExportPlan, output: File, onProgress: (Int?) -> Unit): RenderOutcome
}

/** Probes a finished file to confirm it's a playable video matching the plan. */
public fun interface OutputVerifier {
    public suspend fun verify(file: File, plan: ExportPlan): VerifyOutcome
}

/** Where a published file ended up. [displayName] is the name actually used (the user may rename it in the file picker). */
public data class PublishedFile(val uri: String, val displayName: String)

/** Copies a verified file to its destination, or throws an IOException. */
public fun interface ExportPublisher {
    public suspend fun publish(file: File, destination: ExportDestination, displayName: String): PublishedFile
}

/** Free bytes where the temporary file is written. */
public fun interface FreeSpace {
    public fun bytesAvailable(dir: File): Long
}

/** Progress of one export, as reported by [ExportPipeline.run]. */
public sealed interface ExportStatus {
    /** Checking free space and preparing. */
    public data object Preparing : ExportStatus

    /** Rendering. [percent] comes straight from the renderer, or is null when it can't tell. */
    public data class Rendering(val percent: Int?) : ExportStatus

    /** Reading the finished file back to confirm it plays. */
    public data object Verifying : ExportStatus

    /** Copying to the chosen destination. */
    public data object Saving : ExportStatus

    public data class Completed(val result: ExportedVideo) : ExportStatus
    public data class Failed(val error: ExportError, val detail: String) : ExportStatus
}

/**
 * Runs one export: render to a temporary file, verify it, then publish it.
 *
 * [ExportStatus.Completed] is emitted only after the verifier has read the
 * file back as a valid video and the publisher has copied it. Every other
 * path ends in [ExportStatus.Failed] or cancellation. The temporary file is
 * always deleted, including on cancellation.
 */
public class ExportPipeline(
    private val renderer: VideoRenderer,
    private val verifier: OutputVerifier,
    private val publisher: ExportPublisher,
    private val workDir: File,
    private val freeSpace: FreeSpace = FreeSpace { it.usableSpace },
) {

    public fun run(plan: ExportPlan, destination: ExportDestination, displayName: String): Flow<ExportStatus> = channelFlow {
        suspend fun emit(status: ExportStatus) = send(status)
        emit(ExportStatus.Preparing)
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            emit(ExportStatus.Failed(ExportError.OUTPUT_WRITE_FAILED, "Can't create ${workDir.name}"))
            return@channelFlow
        }
        val needed = estimatedBytes(plan)
        val available = freeSpace.bytesAvailable(workDir)
        if (available < needed) {
            emit(ExportStatus.Failed(ExportError.STORAGE_FULL, "Needs about $needed bytes, $available free"))
            return@channelFlow
        }

        val output = File(workDir, "export-${UUID.randomUUID()}.mp4")
        try {
            emit(ExportStatus.Rendering(null))
            var lastPercent: Int? = null
            val outcome = renderer.render(plan, output) { percent ->
                val clamped = percent?.coerceIn(0, 100)
                if (clamped != lastPercent) {
                    lastPercent = clamped
                    trySend(ExportStatus.Rendering(clamped))
                }
            }
            if (outcome is RenderOutcome.Failed) {
                emit(ExportStatus.Failed(outcome.error, outcome.detail))
                return@channelFlow
            }

            emit(ExportStatus.Verifying)
            if (!output.isFile || output.length() == 0L) {
                emit(ExportStatus.Failed(ExportError.INVALID_OUTPUT, "The renderer reported success but wrote no file"))
                return@channelFlow
            }
            val verified = when (val check = verifier.verify(output, plan)) {
                is VerifyOutcome.Invalid -> {
                    emit(ExportStatus.Failed(ExportError.INVALID_OUTPUT, check.detail))
                    return@channelFlow
                }
                is VerifyOutcome.Valid -> check.video
            }

            emit(ExportStatus.Saving)
            val published = try {
                publisher.publish(output, destination, displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ExportStatus.Failed(if (isNoSpace(e)) ExportError.STORAGE_FULL else ExportError.SAVE_FAILED, e.message ?: e.javaClass.simpleName))
                return@channelFlow
            }
            emit(ExportStatus.Completed(ExportedVideo(published.uri, published.displayName, destination, verified)))
        } finally {
            output.delete()
        }
    }

    public companion object {
        /**
         * A generous size estimate, used only to refuse exports that can't
         * fit: about 0.2 bits per pixel per frame of video (above typical
         * H.264 device encoder output), plus 256 kbit/s for audio, plus 10%.
         */
        public fun estimatedBytes(plan: ExportPlan): Long {
            val seconds = plan.durationUs / 1_000_000.0
            val videoBitsPerSecond = plan.settings.widthPx.toDouble() * plan.settings.heightPx * plan.settings.outputFps * 0.2
            val audioBitsPerSecond = if (plan.hasAudio) 256_000.0 else 0.0
            return ((videoBitsPerSecond + audioBitsPerSecond) * seconds / 8 * 1.1).toLong()
        }

        private fun isNoSpace(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space left") == true }
    }
}
