package com.editor.veyro.media.render

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoGraph
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.timeline.export.AudioSegment
import com.editor.veyro.core.timeline.export.ExportPlan
import com.editor.veyro.core.timeline.export.HandlePiece
import com.editor.veyro.core.timeline.export.SegmentGain
import com.editor.veyro.core.timeline.export.TransitionPlan
import com.editor.veyro.core.timeline.export.VideoSegment
import com.editor.veyro.media.render.overlay.LiveOverlays
import com.editor.veyro.media.render.overlay.StickerImages
import com.editor.veyro.media.render.overlay.TimelineOverlay
import com.editor.veyro.media.render.transform.ClipTransformEffects
import com.editor.veyro.media.render.transform.LiveTransform
import com.editor.veyro.media.render.transition.TransitionCompositorSettings
import com.editor.veyro.media.render.transition.TransitionStage

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
 * - Each picture item is placed on the output canvas by its own transform
 *   ([ClipTransformEffects]: crop, fit or fill, scale, rotation about the
 *   anchor, position, opacity), evaluated with its keyframes for every
 *   frame at the frame's own time, so every item leaves its effects at the
 *   chosen output size. The composition-wide [Presentation] after them is
 *   then a no-op safety net that keeps the output at that size.
 * - Text and stickers ([ExportPlan.overlays]) are drawn after it, by one
 *   composition-wide [OverlayEffect] ([TimelineOverlay]), so they sit on the
 *   output canvas, in timeline time, over photos and videos alike.
 * - Transitions ([TransitionPlan]): Media3 has no transitions, and a
 *   sequence plays its items strictly one after another, so two pictures
 *   can only be on screen together from two sequences. Sequence 2 is the
 *   *handle layer*: video only, empty (gaps) except during transitions,
 *   where it plays the other picture from the media beyond its clip's edge,
 *   placed by that clip's transform. Media3's compositor stacks the main
 *   sequence (its input 0, drawn on top) over it
 *   ([TransitionCompositorSettings] hides it outside transitions), and a
 *   [TransitionStage] on every item that takes part shapes its layer:
 *   opacity, wipe edge, fade, slide, zoom, spin. The audio sequences come
 *   after it.
 *
 * Every item has its source duration set: CompositionPlayer requires it.
 */
@OptIn(UnstableApi::class)
public object CompositionFactory {

    /** The composition Transformer exports. HDR sources are tone-mapped to SDR on Android 10+. */
    public fun forExport(plan: ExportPlan, context: Context): Composition =
        build(
            plan,
            context,
            toneMapHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            transforms = fixedTransforms(plan),
            overlays = LiveOverlays(plan.overlays),
        )

    /**
     * The composition the editor preview plays. Same sequences, items, gains
     * and overlays as [forExport].
     *
     * HDR tone-mapping is normally left to the display, but with overlays
     * or transitions the preview tone-maps with OpenGL, as export does:
     * Media3 can only blend plain bitmaps onto SDR frames (HDR needs Android
     * 14 gain-map images), and its compositor rejects HDR input. CompositionPlayer supports the OpenGL mode; it lacks only the
     * MediaCodec one.
     *
     * [transforms] holds one [LiveTransform] per video segment, in order.
     * The player reads them on every frame, so the editor can move, scale,
     * turn or crop a clip in the real render without a new composition.
     * Without them, each segment's planned transform is used.
     *
     * [overlays] likewise lets the editor change text and stickers, or hide
     * the one being edited, without a new composition. Without it, the
     * plan's overlays are drawn.
     */
    public fun forPreview(
        plan: ExportPlan,
        context: Context,
        transforms: List<LiveTransform>? = null,
        overlays: LiveOverlays? = null,
    ): Composition {
        require(transforms == null || transforms.size == plan.video.size) { "One transform per video segment" }
        return build(
            plan,
            context,
            // Media3 blends overlays and composites sequences on SDR frames only.
            toneMapHdr = (plan.overlays.isNotEmpty() || plan.transitions.isNotEmpty()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            transforms = transforms ?: fixedTransforms(plan),
            overlays = overlays ?: LiveOverlays(plan.overlays),
        )
    }

    /** Each segment's placement over time, as planned. */
    public fun fixedTransforms(plan: ExportPlan): List<LiveTransform> = plan.segmentTransforms().map(::LiveTransform)

    /**
     * The video graph the preview's CompositionPlayer needs: one that
     * composites several picture sequences (the handle layer of
     * transitions). CompositionPlayer's default handles a single sequence
     * only and would drop the second one. Transformer picks the
     * multiple-input graph by itself whenever a composition has more than
     * one picture sequence.
     */
    public fun previewVideoGraphFactory(): VideoGraph.Factory = MultipleInputVideoGraph.Factory()

    private fun build(plan: ExportPlan, context: Context, toneMapHdr: Boolean, transforms: List<LiveTransform>, overlays: LiveOverlays): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        val transitions = TransitionPlan(plan)
        val width = plan.settings.widthPx
        val height = plan.settings.heightPx
        if (plan.video.isNotEmpty()) {
            val fps = plan.settings.outputFps
            val trackTypes = if (plan.includeClipAudio) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
            sequences += EditedMediaItemSequence.Builder(trackTypes)
                .addItems(
                    plan.video.mapIndexed { index, segment ->
                        val placement = ClipTransformEffects.create(transforms[index], width, height)
                        val effects = if (transitions.hasTransition(index)) {
                            placement + TransitionStage({ timeUs -> transitions.mainLook(index, timeUs, width, height) }, width, height)
                        } else {
                            placement
                        }
                        videoItem(segment, fps, keepAudio = plan.includeClipAudio, placement = effects)
                    },
                )
                .build()
            if (!transitions.isEmpty) sequences += handleSequence(plan, transitions, transforms, fps)
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
            val presentation = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            val overlayEffects = if (plan.overlays.isEmpty()) {
                emptyList()
            } else {
                // A new overlay per composition: it keeps the current frame's bitmap and GL texture.
                listOf(OverlayEffect(listOf(TimelineOverlay(overlays, width, height, StickerImages(context)))))
            }
            builder.setEffects(Effects(emptyList(), listOf(presentation) + overlayEffects))
            if (!transitions.isEmpty) builder.setVideoCompositorSettings(TransitionCompositorSettings(transitions, width, height))
        }
        if (toneMapHdr) builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        return builder.build()
    }

    /**
     * The handle layer: gaps, and during each transition the other picture
     * from beyond its clip's edge. Silent, and placed like its clip, with the
     * placement held at the clip's edge (its keyframes don't run on past
     * the clip).
     */
    private fun handleSequence(plan: ExportPlan, transitions: TransitionPlan, transforms: List<LiveTransform>, fps: Int): EditedMediaItemSequence {
        val width = plan.settings.widthPx
        val height = plan.settings.heightPx
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            // It starts with a gap, so Media3 must be told the sequence has pictures before its first item.
            .experimentalSetForceVideoTrack(true)
        var cursor = 0L
        transitions.pieces.forEach { piece ->
            if (piece.startUs > cursor) sequence.addGap(piece.startUs - cursor)
            val placement = ClipTransformEffects.create(transforms[piece.segmentIndex].clampedTo(transitions.clipRangeUs(piece.segmentIndex)), width, height)
            val shape = TransitionStage({ timeUs -> transitions.pieceLook(piece, timeUs, width, height) }, width, height)
            sequence.addItem(handleItem(plan.video[piece.segmentIndex], piece, fps, placement + shape))
            cursor = piece.endUs
        }
        return sequence.build()
    }

    private fun handleItem(segment: VideoSegment, piece: HandlePiece, fps: Int, effects: List<Effect>): EditedMediaItem =
        when (segment.kind) {
            MediaKind.IMAGE -> {
                val mediaItem = MediaItem.Builder()
                    .setUri(segment.uri)
                    .setImageDurationMs(ceilMs(piece.durationUs))
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(piece.durationUs)
                    .setFrameRate(fps)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
            }
            else ->
                EditedMediaItem.Builder(clipped(segment.uri, piece.sourceStartUs, piece.sourceEndUs))
                    .setDurationUs(maxOf(segment.sourceDurationUs, piece.sourceEndUs))
                    .setFrameRate(fps)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
        }

    private fun videoItem(segment: VideoSegment, fps: Int, keepAudio: Boolean, placement: List<Effect>): EditedMediaItem =
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
                    .setEffects(Effects(emptyList(), placement))
                    .build()
            }
            else -> {
                val gain = segment.audio
                EditedMediaItem.Builder(clipped(segment.uri, segment.sourceStartUs, segment.sourceEndUs))
                    .setDurationUs(segment.sourceDurationUs)
                    // A maximum for video: faster sources drop frames, slower ones are kept as they are.
                    .setFrameRate(fps)
                    .setRemoveAudio(!keepAudio || gain == null)
                    .setEffects(Effects(if (gain != null) audioChain(gain) else emptyList(), placement))
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
