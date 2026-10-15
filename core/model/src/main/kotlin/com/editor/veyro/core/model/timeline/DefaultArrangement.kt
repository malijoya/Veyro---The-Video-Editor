package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.time.FrameRate

/**
 * Lays out media as timeline clips:
 * - videos and photos back to back on the main Video track, in import order
 * - audio files back to back on the Music track, starting at 0
 * - an empty Voiceover track, for narration moved there from the Music track
 *
 * [build] gives a project's starting timeline. [append] adds newly imported
 * media to an existing, possibly edited, timeline. Clip IDs come from media
 * IDs, so both are deterministic.
 */
public object DefaultArrangement {
    /** How long a photo stays on screen when first placed, unless the project's settings say otherwise. */
    public const val PHOTO_DURATION_US: Long = 3_000_000

    public val VIDEO_TRACK_ID: TrackId = TrackId("video-main")
    /** The music track. Its ID predates track roles, so it stays "audio-1" for existing projects. */
    public val AUDIO_TRACK_ID: TrackId = TrackId("audio-1")
    public val VOICEOVER_TRACK_ID: TrackId = TrackId("audio-voice")

    /**
     * The timeline the editor shows: the stored one, or the default
     * arrangement if none was saved yet. Its frame grid always comes from the
     * project's settings. Timelines saved before track roles existed get
     * them here (see [withStandardTracks]).
     */
    public fun timelineFor(project: Project): Timeline =
        withStandardTracks(project.timeline ?: build(project)).copy(frameRate = project.settings.frameRate)

    /**
     * Makes sure the timeline has a Music and a Voiceover track:
     * - The first audio track without a role becomes Music, if there's none.
     * - An empty Voiceover track is added after the audio tracks, if there's none.
     * Clips are never moved.
     */
    public fun withStandardTracks(timeline: Timeline): Timeline {
        var tracks = timeline.tracks
        if (tracks.none { it.role == AudioRole.MUSIC }) {
            val index = tracks.indexOfFirst { it.kind == TrackKind.AUDIO && it.role == null }
            tracks = if (index >= 0) {
                tracks.mapIndexed { i, track -> if (i == index) track.copy(role = AudioRole.MUSIC) else track }
            } else {
                tracks + Track(freeId(tracks, AUDIO_TRACK_ID), TrackKind.AUDIO, emptyList(), role = AudioRole.MUSIC)
            }
        }
        if (tracks.none { it.role == AudioRole.VOICEOVER }) {
            val after = tracks.indexOfLast { it.kind == TrackKind.AUDIO }
            val voice = Track(freeId(tracks, VOICEOVER_TRACK_ID), TrackKind.AUDIO, emptyList(), role = AudioRole.VOICEOVER)
            tracks = tracks.toMutableList().apply { add(after + 1, voice) }
        }
        return if (tracks === timeline.tracks) timeline else timeline.copy(tracks = tracks)
    }

    private fun freeId(tracks: List<Track>, preferred: TrackId): TrackId {
        if (tracks.none { it.id == preferred }) return preferred
        var n = 2
        while (tracks.any { it.id.value == "${preferred.value}-$n" }) n++
        return TrackId("${preferred.value}-$n")
    }

    public fun build(project: Project): Timeline {
        val empty = Timeline(
            tracks = listOf(
                Track(VIDEO_TRACK_ID, TrackKind.VIDEO, emptyList()),
                Track(AUDIO_TRACK_ID, TrackKind.AUDIO, emptyList(), role = AudioRole.MUSIC),
                Track(VOICEOVER_TRACK_ID, TrackKind.AUDIO, emptyList(), role = AudioRole.VOICEOVER),
            ),
            frameRate = project.settings.frameRate,
        )
        return append(empty, project.media, project.settings.photoDurationUs)
    }

    /**
     * Adds [media] at the end of the main track (video and photos) or the
     * audio track (audio). Media already on the timeline (including images
     * used as stickers) is skipped.
     */
    public fun append(timeline: Timeline, media: List<MediaRef>, photoDurationUs: Long = PHOTO_DURATION_US): Timeline {
        val onTimeline = timeline.tracks.flatMap { track -> track.clips.mapNotNull { it.mediaId } }.toSet()
        var result = timeline
        for (item in media) {
            if (item.id in onTimeline) continue
            val (trackKind, durationUs) = when (item.kind) {
                MediaKind.VIDEO -> TrackKind.VIDEO to item.durationUs
                MediaKind.IMAGE -> TrackKind.VIDEO to photoDurationUs
                MediaKind.AUDIO -> TrackKind.AUDIO to item.durationUs
            }
            if (durationUs <= 0) continue
            // New audio goes to the Music track. Voiceover is chosen per clip in the editor.
            val track = (if (trackKind == TrackKind.AUDIO) result.tracks.firstOrNull { it.role == AudioRole.MUSIC } else null)
                ?: result.tracks.firstOrNull { it.kind == trackKind }
                ?: continue
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
