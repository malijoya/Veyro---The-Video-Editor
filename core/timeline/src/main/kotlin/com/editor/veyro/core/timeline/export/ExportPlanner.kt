package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.audio.GainEnvelope
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transition.CutSide
import com.editor.veyro.core.model.transition.TransitionResolver
import kotlin.math.roundToInt

/**
 * Turns a timeline into an [ExportPlan]. Pure and platform-independent.
 *
 * What is rendered:
 * - The main track, in order, with each clip's exact source range and its
 *   transform (position, scale, rotation, anchor, crop, opacity) and
 *   keyframes, evaluated per frame ([SegmentTransform]). Video
 *   clips keep their own sound, with the clip's and the track's volume,
 *   mute and fades. Photos are silent.
 * - Every audio track (music, voiceover), each with its gaps, mixed under
 *   the picture at the clips' timeline positions. Audio past the end of the
 *   picture is cut there. Muted tracks and silent clips are left out.
 * - A mix headroom curve ([MixHeadroom]) that turns overlapping sound down
 *   just enough not to clip, from the media's measured peaks.
 * - Text and stickers from the overlay tracks, bottom layer first, cut at
 *   the end of the picture, with their keyframes. Empty text, and overlays
 *   whose opacity is 0 on every frame, are skipped.
 *   A sticker whose image is unavailable blocks the export, like any other
 *   missing file.
 * - Transitions between main-track clips, fitted to their cuts on the
 *   timeline's frame grid ([TransitionResolver]): shortened where clips are
 *   short or lack media past their edges, left out where nothing fits
 *   (with [ExportWarning.TRANSITIONS_SHORTENED]). The timeline's timing
 *   doesn't change: the cut stays put, and so does all sound.
 *
 * Not rendered yet (reported as [ExportWarning]s): extra video tracks
 * (picture-in-picture) and clip effects.
 */
public object ExportPlanner {

    /**
     * @param media the project's media.
     * @param unavailable media whose file can't be read right now (missing,
     *   or access revoked). Clips using them block the plan.
     * @param waveforms measured peaks per media, for the mix headroom. Media
     *   without one is assumed to peak at full scale.
     * @param requireVideo true for export. The editor preview passes false,
     *   so an audio-only project still plays.
     */
    public fun plan(
        timeline: Timeline,
        media: List<MediaRef>,
        settings: ExportSettings,
        unavailable: Set<MediaId> = emptySet(),
        waveforms: (MediaId) -> Waveform? = { null },
        requireVideo: Boolean = true,
    ): ExportPlanResult {
        val mediaById = media.associateBy { it.id }
        val main = timeline.mainTrack
        val hasVideo = main != null && main.clips.isNotEmpty()
        val audioTracks = timeline.tracks.filter { it.kind == TrackKind.AUDIO }
        if (!hasVideo && (requireVideo || audioTracks.all { it.clips.isEmpty() })) {
            return ExportPlanResult.Blocked(setOf(ExportBlocker.NO_VIDEO))
        }

        val blockers = mutableSetOf<ExportBlocker>()
        val problemFiles = linkedSetOf<String>()
        val warnings = mutableSetOf<ExportWarning>()
        if (timeline.tracks.any { it.kind == TrackKind.VIDEO && it.id != main?.id && it.clips.isNotEmpty() }) {
            warnings += ExportWarning.EXTRA_TRACKS_IGNORED
        }

        fun usable(id: MediaId): MediaRef? {
            val ref = mediaById[id]
            if (ref == null || id in unavailable) {
                blockers += ExportBlocker.MEDIA_UNAVAILABLE
                problemFiles += ref?.let(::label) ?: id.value
                return null
            }
            return ref
        }

        fun withinSource(ref: MediaRef, sourceEndUs: Long): Boolean {
            // An unknown duration (0) can't be checked; the renderer reports a real overrun.
            if (ref.kind == MediaKind.IMAGE || ref.durationUs <= 0 || sourceEndUs <= ref.durationUs) return true
            blockers += ExportBlocker.SOURCE_RANGE_INVALID
            problemFiles += label(ref)
            return false
        }

        // ---- Picture track, and the sound of its videos ----
        val pendingVideo = main?.clips.orEmpty().mapNotNull { clip ->
            if (clip.effects.any { it.enabled }) warnings += ExportWarning.EFFECTS_NOT_RENDERED
            val ref = usable(clip.mediaId ?: return@mapNotNull null) ?: return@mapNotNull null
            if (ref.kind == MediaKind.AUDIO) {
                // The editor never puts audio on the main track; a hand-edited project might.
                blockers += ExportBlocker.MEDIA_UNAVAILABLE
                problemFiles += label(ref)
                return@mapNotNull null
            }
            if (!withinSource(ref, clip.sourceOutUs)) return@mapNotNull null
            // A video without an audio stream simply contributes no sound.
            val envelope = if (ref.kind == MediaKind.VIDEO && ref.audio != null) {
                GainEnvelope(clip.durationUs, clip.sourceInUs, clip.audio, main!!.audio).takeUnless { it.isSilent }
            } else {
                null
            }
            PendingVideo(ref, clip, envelope)
        }

        // ---- Audio tracks ----
        val endUs = if (hasVideo) main!!.endUs else audioTracks.maxOf { it.endUs }
        val pendingTracks = audioTracks.mapNotNull { track ->
            val pieces = track.clips.mapNotNull { clip ->
                if (clip.effects.any { it.enabled }) warnings += ExportWarning.EFFECTS_NOT_RENDERED
                if (clip.startUs >= endUs) {
                    warnings += ExportWarning.AUDIO_TRIMMED_TO_VIDEO
                    return@mapNotNull null
                }
                val ref = usable(clip.mediaId ?: return@mapNotNull null) ?: return@mapNotNull null
                if (!withinSource(ref, clip.sourceOutUs)) return@mapNotNull null
                if (clip.endUs > endUs) warnings += ExportWarning.AUDIO_TRIMMED_TO_VIDEO
                // The envelope covers the whole clip, so a fade-out past the cut keeps its shape up to the cut.
                val envelope = GainEnvelope(clip.durationUs, clip.sourceInUs, clip.audio, track.audio)
                if (envelope.isSilent) null else PendingAudio(ref, clip.startUs, minOf(clip.endUs, endUs), envelope)
            }
            pieces.takeIf { it.isNotEmpty() }
        }

        // ---- Text and stickers, bottom layer first ----
        val overlays = if (!hasVideo) {
            emptyList() // An audio-only preview has no picture to draw on.
        } else {
            timeline.overlayTracks.asReversed().flatMap { track ->
                track.clips.mapNotNull { clip ->
                    val overlay = clip.overlay ?: return@mapNotNull null
                    if (clip.effects.any { it.enabled }) warnings += ExportWarning.EFFECTS_NOT_RENDERED
                    if (clip.startUs >= endUs) {
                        warnings += ExportWarning.OVERLAYS_TRIMMED_TO_VIDEO
                        return@mapNotNull null
                    }
                    if (clip.endUs > endUs) warnings += ExportWarning.OVERLAYS_TRIMMED_TO_VIDEO
                    val image = clip.mediaId?.let { id -> usable(id) ?: return@mapNotNull null }
                    if (image != null && image.kind != MediaKind.IMAGE) {
                        // The editor only makes stickers from images; a hand-edited project might not.
                        blockers += ExportBlocker.MEDIA_UNAVAILABLE
                        problemFiles += label(image)
                        return@mapNotNull null
                    }
                    // Skip what never shows: empty text, or an opacity that is 0 on every frame.
                    val content = overlay.content
                    val empty = content is OverlayContent.Text && content.text.isBlank()
                    if (empty || !clip.animation.everVisible(overlay.transform.opacity)) return@mapNotNull null
                    OverlayLayer(
                        clipId = clip.id.value,
                        startUs = clip.startUs,
                        endUs = minOf(clip.endUs, endUs),
                        overlay = overlay,
                        imageUri = image?.uri,
                        imageWidthPx = image?.displayWidthPx ?: 0,
                        imageHeightPx = image?.displayHeightPx ?: 0,
                        imageRotationDegrees = image?.rotationDegrees ?: 0,
                        animation = clip.animation,
                    )
                }
            }
        }
        if (blockers.isNotEmpty()) return ExportPlanResult.Blocked(blockers, problemFiles.toList())

        // ---- Transitions between consecutive picture segments ----
        val transitions = pendingVideo.zipWithNext().mapIndexedNotNull { index, (outgoing, incoming) ->
            val transition = outgoing.clip.transitionOut ?: return@mapIndexedNotNull null
            val window = TransitionResolver.resolve(
                transition = transition,
                cutUs = outgoing.clip.endUs,
                outgoing = CutSide(outgoing.clip.durationUs, handleAfter(outgoing)),
                incoming = CutSide(incoming.clip.durationUs, handleBefore(incoming)),
                frameRate = timeline.frameRate,
            )
            if (window.limit != null) warnings += ExportWarning.TRANSITIONS_SHORTENED
            window.takeIf { it.isActive }?.let { PlannedTransition(index, it) }
        }

        // An audio-only preview where every track is muted has nothing to play.
        if (pendingVideo.isEmpty() && pendingTracks.isEmpty()) return ExportPlanResult.Blocked(setOf(ExportBlocker.NO_VIDEO))

        // ---- Mix headroom from measured peaks ----
        val sources = pendingVideo.mapNotNull { v -> v.envelope?.let { MixSource(v.clip.startUs, it, waveforms(v.ref.id)) } } +
            pendingTracks.flatten().map { a -> MixSource(a.startUs, a.envelope, waveforms(a.ref.id)) }
        val headroom = MixHeadroom.compute(sources, endUs)
        if (!headroom.isUnity) warnings += ExportWarning.MIX_LOWERED_TO_PREVENT_CLIPPING
        if (sources.size > 1 && sources.any { it.waveform == null }) warnings += ExportWarning.AUDIO_NOT_ANALYSED

        val video = pendingVideo.map { v ->
            VideoSegment(
                uri = v.ref.uri,
                kind = v.ref.kind,
                sourceStartUs = if (v.ref.kind == MediaKind.IMAGE) 0 else v.clip.sourceInUs,
                durationUs = v.clip.durationUs,
                sourceDurationUs = sourceLength(v.ref, v.clip.sourceOutUs),
                audio = v.envelope?.let { SegmentGain(it, v.clip.startUs, headroom) },
                transform = v.clip.transform,
                sourceWidthPx = v.ref.displayWidthPx,
                sourceHeightPx = v.ref.displayHeightPx,
                animation = v.clip.animation,
            )
        }
        val audio = pendingTracks.map { pieces ->
            val segments = mutableListOf<AudioSegment>()
            var cursor = 0L
            for (piece in pieces) {
                if (piece.startUs > cursor) segments += AudioSegment.Gap(piece.startUs - cursor)
                segments += AudioSegment.Clip(
                    uri = piece.ref.uri,
                    sourceStartUs = piece.envelope.sourceInUs,
                    durationUs = piece.endUs - piece.startUs,
                    sourceDurationUs = sourceLength(piece.ref, piece.envelope.sourceInUs + piece.envelope.durationUs),
                    gain = SegmentGain(piece.envelope, piece.startUs, headroom),
                )
                cursor = piece.endUs
            }
            // Silence fills the rest, so every sequence ends together.
            if (cursor < endUs) segments += AudioSegment.Gap(endUs - cursor)
            segments.toList()
        }
        return ExportPlanResult.Ready(ExportPlan(settings, video, audio, warnings, overlays, transitions))
    }

    /** Media after the clip's out-point; null (unlimited) for photos, none when the file's length is unknown. */
    private fun handleAfter(video: PendingVideo): Long? = when {
        video.ref.kind == MediaKind.IMAGE -> null
        video.ref.durationUs <= 0 -> 0
        else -> (video.ref.durationUs - video.clip.sourceOutUs).coerceAtLeast(0)
    }

    /** Media before the clip's in-point; null (unlimited) for photos. */
    private fun handleBefore(video: PendingVideo): Long? = if (video.ref.kind == MediaKind.IMAGE) null else video.clip.sourceInUs

    private class PendingVideo(val ref: MediaRef, val clip: Clip, val envelope: GainEnvelope?)

    private class PendingAudio(val ref: MediaRef, val startUs: Long, val endUs: Long, val envelope: GainEnvelope)

    private fun sourceLength(ref: MediaRef, sourceEndUs: Long): Long = maxOf(ref.durationUs, sourceEndUs, 1)

    private fun label(ref: MediaRef): String = ref.displayName ?: ref.uri
}

/** A choice of output size, shown in the export screen. */
public data class ExportResolution(val widthPx: Int, val heightPx: Int) {
    val shortSidePx: Int get() = minOf(widthPx, heightPx)
}

/** The choices offered on the export screen. */
public object ExportOptions {

    /** Short sides offered below the project's canvas. */
    private val SHORT_SIDES = listOf(2_160, 1_440, 1_080, 720, 480)

    /** Frame rates offered besides the project's own. */
    public val FRAME_RATES: List<FrameRate> = listOf(FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_30, FrameRate.FPS_60)

    /**
     * The project canvas first, then the standard sizes smaller than it,
     * all with the canvas's shape and even sides.
     */
    public fun resolutionsFor(canvasWidthPx: Int, canvasHeightPx: Int): List<ExportResolution> {
        val canvas = ExportResolution(even(canvasWidthPx), even(canvasHeightPx))
        val scaled = SHORT_SIDES.filter { it < canvas.shortSidePx }.map { short ->
            val scale = short.toDouble() / canvas.shortSidePx
            ExportResolution(even((canvas.widthPx * scale).roundToInt()), even((canvas.heightPx * scale).roundToInt()))
        }
        return (listOf(canvas) + scaled).distinct()
    }

    /** The project's rate first, then the standard rates. */
    public fun frameRatesFor(projectRate: FrameRate): List<FrameRate> = (listOf(projectRate) + FRAME_RATES).distinct()

    private fun even(value: Int): Int =
        ((value + 1) / 2 * 2).coerceIn(ExportSettings.MIN_DIMENSION_PX, ExportSettings.MAX_DIMENSION_PX)
}
