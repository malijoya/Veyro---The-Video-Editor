package com.editor.veyro.media.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.editor.veyro.core.timeline.export.ExportPlan
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Confirms an exported file is a real, playable video matching the plan,
 * using the platform's own demuxer and decoder:
 * - `MediaExtractor` must find a video track (and an audio track when the
 *   plan has sound).
 * - The duration must be within [toleranceUs] of the plan.
 * - `MediaMetadataRetriever` must decode a frame.
 *
 * The reported size and duration come from the file, so the completion
 * screen shows what was actually written (Transformer may fall back to a
 * smaller size on encoders that can't do the requested one).
 */
public class AndroidOutputVerifier(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OutputVerifier {

    override suspend fun verify(file: File, plan: ExportPlan): VerifyOutcome = withContext(ioDispatcher) {
        if (!file.isFile || file.length() == 0L) return@withContext VerifyOutcome.Invalid("Output file is missing or empty")
        val tracks = try {
            readTracks(file)
        } catch (e: Exception) {
            return@withContext VerifyOutcome.Invalid("Not a readable MP4: ${e.message}")
        }
        val video = tracks.firstOrNull { it.mime.startsWith("video/") }
            ?: return@withContext VerifyOutcome.Invalid("No video track")
        val audio = tracks.firstOrNull { it.mime.startsWith("audio/") }
        if (plan.hasAudio && audio == null) return@withContext VerifyOutcome.Invalid("Expected an audio track, found none")

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationUs = video.durationUs.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1_000)
                ?: return@withContext VerifyOutcome.Invalid("Unknown duration")
            val expectedUs = plan.durationUs
            if (abs(durationUs - expectedUs) > toleranceUs(expectedUs)) {
                return@withContext VerifyOutcome.Invalid("Duration ${durationUs}us, expected ${expectedUs}us")
            }
            // The first frame must decode, or nothing will play it.
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext VerifyOutcome.Invalid("No decodable video frame")
            frame.recycle()

            val sideways = rotation % 180 != 0
            VerifyOutcome.Valid(
                VerifiedVideo(
                    durationUs = durationUs,
                    widthPx = if (sideways) video.height else video.width,
                    heightPx = if (sideways) video.width else video.height,
                    videoMime = video.mime,
                    audioMime = audio?.mime,
                    sizeBytes = file.length(),
                ),
            )
        } catch (e: RuntimeException) {
            VerifyOutcome.Invalid("Couldn't read the video: ${e.message}")
        } finally {
            retriever.release()
        }
    }

    private class Track(val mime: String, val durationUs: Long, val width: Int, val height: Int)

    private fun readTracks(file: File): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                Track(
                    mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                    durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0,
                    width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0,
                    height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0,
                )
            }
        } finally {
            extractor.release()
        }
    }

    public companion object {
        /**
         * How far the written duration may differ from the plan: half a
         * second or 3%, whichever is larger. Encoders end tracks on whole
         * frames and audio frames, so exact equality isn't expected; a file
         * cut short by a failure is far outside this.
         */
        public fun toleranceUs(expectedUs: Long): Long = maxOf(500_000L, expectedUs * 3 / 100)
    }
}
