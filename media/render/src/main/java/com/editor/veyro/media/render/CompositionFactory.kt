package com.editor.veyro.media.render

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.timeline.export.AudioSegment
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.SegmentGain
import com.editor.veyro.core.timeline.export.VideoSegment
import com.editor.veyro.media.render.overlay.StickerImages
import com.editor.veyro.media.render.overlay.TimelineOverlay

/**
 * Translates an [ExportPlan] into a Media3 [Composition], for both export
 * (Transformer) and the editor preview (CompositionPlayer). No editing
 * decisions are made here beyond what Media3 requires.
 *
 * - Sequence 1, the main track: every segment in order, with its source
 *   range as a clipping configuration. It carries audio when any segment has
 *   sound; Media3 then fills photos and silent videos with silence.
 * - One more sequence per audio track (music, voiceover), with gaps as
 *   silence, mixed under sequence 1. All sequences start at 0, so each
 *   sound plays at its timeline position.
 * - Every audio item is converted to 16-bit stereo PCM. Media3 requires all
 *   audio in a composition to share sample format and channel count.
 * - Each item's gain (volume, mute, fades, keyframes and the mix headroom
 *   from [SegmentGain]) runs in a Media3 [GainProcessor]. Media3 positions
 *   per-item audio processors from the clipped item's start in both
 *   Transformer and CompositionPlayer, so fades land in the same place in
 *   preview and export, including after seeks. Items at unity gain skip it.
 * - A composition-wide [Presentation] scales and letterboxes everything to
 *   the chosen size.
 * - Text and stickers ([ExportPlan.overlays]) are drawn after it, by one
 *   composition-wide [OverlayEffect] ([TimelineOverlay]), so they sit on the
 *   output canvas, in timeline time, over photos and videos alike.
 *
 * Every item has its source duration set: CompositionPlayer requires it.
 */
@OptIn(UnstableApi::class)
public object CompositionFactory {

    /** The composition Transformer exports. HDR sources are tone-mapped to SDR on Android 10+. */
    public fun forExport(plan: ExportPlan, context: Context): Composition =
        build(plan, context, toneMapHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

    /**
     * The composition the editor preview plays. Same sequences, items, gains
     * and overlays as [forExport].
     *
     * HDR tone-mapping is normally left to the display, but with overlays
     * the preview tone-maps with OpenGL, as export does: Media3 can only
     * blend plain bitmaps onto SDR frames (HDR needs Android 14 gain-map
     * images). CompositionPlayer supports the OpenGL mode; it lacks only the
     * MediaCodec one.
     */
    public fun forPreview(plan: ExportPlan, context: Context): Composition =
        build(plan, context, toneMapHdr = plan.overlays.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

    private fun build(plan: ExportPlan, context: Context, toneMapHdr: Boolean): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        if (plan.video.isNotEmpty()) {
            val fps = plan.settings.outputFps
            val trackTypes = if (plan.includeClipAudio) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
            sequences += EditedMediaItemSequence.Builder(trackTypes)
                .addItems(plan.video.map { videoItem(it, fps, keepAudio = plan.includeClipAudio) })
                .build()
        }
        plan.audioTracks.forEach { track ->
            val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            track.forEach { segment ->
                when (segment) {
                    is AudioSegment.Gap -> sequence.addGap(segment.durationUs)
                    is AudioSegment.Clip -> sequence.addItem(audioItem(segment))
                }
            }
            sequences += sequence.build()
        }

        val builder = Composition.Builder(sequences)
        if (plan.video.isNotEmpty()) {
            val presentation = Presentation.createForWidthAndHeight(plan.settings.widthPx, plan.settings.heightPx, Presentation.LAYOUT_SCALE_TO_FIT)
            val overlays = if (plan.overlays.isEmpty()) {
                emptyList()
            } else {
                // A new overlay per composition: it keeps the current frame's bitmap and GL texture.
                listOf(OverlayEffect(listOf(TimelineOverlay(plan.overlays, plan.settings.widthPx, plan.settings.heightPx, StickerImages(context)))))
            }
            builder.setEffects(Effects(emptyList(), listOf(presentation) + overlays))
        }
        if (toneMapHdr) builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        return builder.build()
    }

    private fun videoItem(segment: VideoSegment, fps: Int, keepAudio: Boolean): EditedMediaItem =
        when (segment.kind) {
            MediaKind.IMAGE -> {
                // Images need a duration and a frame rate. The image duration is in ms, so the exact µs length is set too.
                val mediaItem = MediaItem.Builder()
                    .setUri(segment.uri)
                    .setImageDurationMs(ceilMs(segment.durationUs))
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(segment.durationUs)
                    .setFrameRate(fps)
                    .build()
            }
            else -> {
                val gain = segment.audio
                EditedMediaItem.Builder(clipped(segment.uri, segment.sourceStartUs, segment.sourceEndUs))
                    .setDurationUs(segment.sourceDurationUs)
                    // A maximum for video: faster sources drop frames, slower ones are kept as they are.
                    .setFrameRate(fps)
                    .setRemoveAudio(!keepAudio || gain == null)
                    .setEffects(Effects(if (gain != null) audioChain(gain) else emptyList(), emptyList()))
                    .build()
            }
        }

    private fun audioItem(segment: AudioSegment.Clip): EditedMediaItem =
        EditedMediaItem.Builder(clipped(segment.uri, segment.sourceStartUs, segment.sourceEndUs))
            .setDurationUs(segment.sourceDurationUs)
            .setEffects(Effects(audioChain(segment.gain), emptyList()))
            .build()

    private fun clipped(uri: String, startUs: Long, endUs: Long): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(startUs)
                    .setEndPositionUs(endUs)
                    .build(),
            )
            .build()

    /**
     * 16-bit, then stereo, then gain. New instances per item: processors are
     * stateful. The gain stage is left out at unity, so untouched clips are
     * bit-exact.
     */
    private fun audioChain(gain: SegmentGain): List<AudioProcessor> {
        val toStereo = ChannelMixingAudioProcessor()
        for (channels in 1..MAX_INPUT_CHANNELS) {
            toStereo.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, OUTPUT_CHANNELS))
        }
        val chain = mutableListOf<AudioProcessor>(ToInt16PcmAudioProcessor(), toStereo)
        if (!gain.isUnity) chain += GainProcessor(SegmentGainProvider(gain))
        return chain
    }

    private fun ceilMs(us: Long): Long = (us + 999) / 1_000

    /** The most channels Media3 has a default stereo down-mix for (5.1). */
    private const val MAX_INPUT_CHANNELS = 6
    private const val OUTPUT_CHANNELS = 2
}

/**
 * Feeds a [SegmentGain] to Media3's [GainProcessor]. Sample positions count
 * from the start of the clipped item, which is offset 0 of the gain.
 *
 * [GainProcessor] requires a unity range whenever the gain is exactly 1, and
 * that range must not be empty, or it would stop advancing. Isolated samples
 * that happen to land on exactly 1 report a one-sample range.
 */
@OptIn(UnstableApi::class)
internal class SegmentGainProvider(private val gain: SegmentGain) : GainProcessor.GainProvider {

    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
        gain.gainAt(samplePosition * US_PER_SECOND / sampleRate)

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        val offsetUs = samplePosition * US_PER_SECOND / sampleRate
        val endUs = gain.unityUntilUs(offsetUs)
        if (endUs == null) {
            return if (gain.gainAt(offsetUs) == 1f) samplePosition + 1 else C.TIME_UNSET
        }
        // First sample at or after the end of the unity stretch.
        val endSample = (endUs * sampleRate + US_PER_SECOND - 1) / US_PER_SECOND
        return maxOf(endSample, samplePosition + 1)
    }

    private companion object {
        const val US_PER_SECOND = 1_000_000L
    }
}
