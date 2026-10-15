package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.time.FrameRate

/**
 * Lays out media as timeline clips:
 * - videos and photos back to back on the main Video track, in import order
 * - audio files back to back on an Audio track, starting at 0
 *
 * [build] gives a project's starting timeline. [append] adds newly imported
 * media to an existing, possibly edited, timeline. Clip IDs come from media
 * IDs, so both are deterministic.
 */
public object DefaultArrangement {
    /** How long a photo stays on screen when first placed, unless the project's settings say otherwise. */
    public const val PHOTO_DURATION_US: Long = 3_000_000

    public val VIDEO_TRACK_ID: TrackId = TrackId("video-main")
    public val AUDIO_TRACK_ID: TrackId = TrackId("audio-1")

    /**
     * The timeline the editor shows: the stored one, or the default
     * arrangement if none was saved yet. Its frame grid always comes from the
     * project's settings.
     */
    public fun timelineFor(project: Project): Timeline =
        (project.timeline ?: build(project)).copy(frameRate = project.settings.frameRate)

    public fun build(project: Project): Timeline {
        val empty = Timeline(
            tracks = listOf(
                Track(VIDEO_TRACK_ID, TrackKind.VIDEO, emptyList()),
                Track(AUDIO_TRACK_ID, TrackKind.AUDIO, emptyList()),
            ),
            frameRate = project.settings.frameRate,
        )
        return append(empty, project.media, project.settings.photoDurationUs)
    }

    /**
     * Adds [media] at the end of the main track (video and photos) or the
     * audio track (audio). Media already on the timeline is skipped.
     */
    public fun append(timeline: Timeline, media: List<MediaRef>, photoDurationUs: Long = PHOTO_DURATION_US): Timeline {
        val onTimeline = timeline.tracks.flatMap { track -> track.clips.map { it.mediaId } }.toSet()
        var result = timeline
        for (item in media) {
            if (item.id in onTimeline) continue
            val (trackKind, durationUs) = when (item.kind) {
                MediaKind.VIDEO -> TrackKind.VIDEO to item.durationUs
                MediaKind.IMAGE -> TrackKind.VIDEO to photoDurationUs
                MediaKind.AUDIO -> TrackKind.AUDIO to item.durationUs
            }
            if (durationUs <= 0) continue
            val track = result.tracks.firstOrNull { it.kind == trackKind } ?: continue
            val clip = Clip(clipIdFor(item.id.value), item.id, startUs = track.endUs, sourceInUs = 0, durationUs = durationUs)
            result = result.withTrack(track.copy(clips = track.clips + clip))
        }
        return result
    }

    /** The edit frame rate: the first video's rate, or 30 fps when no video declares one. */
    public fun frameRateFor(media: List<MediaRef>): FrameRate =
        media.firstNotNullOfOrNull { it.video?.frameRate } ?: FrameRate.FPS_30

    private fun clipIdFor(mediaId: String) = ClipId("clip-$mediaId")
}
