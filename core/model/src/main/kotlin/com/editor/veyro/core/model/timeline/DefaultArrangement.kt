package com.editor.veyro.core.model.timeline

import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.Project

/**
 * Lays out a project's media as a starting timeline:
 * - videos and photos back to back on a Video track, in import order
 * - audio files back to back on an Audio track, starting at 0
 *
 * Until edits are stored (M1), the editor shows this arrangement. It is
 * deterministic: the same project always gives the same clips and IDs, so
 * selection and scroll position survive recomposition and process death.
 */
public object DefaultArrangement {
    /** How long a photo stays on screen when first placed. */
    public const val PHOTO_DURATION_US: Long = 3_000_000

    public val VIDEO_TRACK_ID: TrackId = TrackId("video-main")
    public val AUDIO_TRACK_ID: TrackId = TrackId("audio-1")

    public fun build(project: Project): Timeline {
        val visual = mutableListOf<Clip>()
        val audio = mutableListOf<Clip>()
        var visualEnd = 0L
        var audioEnd = 0L
        for (media in project.media) {
            when (media.kind) {
                MediaKind.VIDEO -> if (media.durationUs > 0) {
                    visual += Clip(clipIdFor(media.id.value), media.id, visualEnd, sourceInUs = 0, durationUs = media.durationUs)
                    visualEnd += media.durationUs
                }
                MediaKind.IMAGE -> {
                    visual += Clip(clipIdFor(media.id.value), media.id, visualEnd, sourceInUs = 0, durationUs = PHOTO_DURATION_US)
                    visualEnd += PHOTO_DURATION_US
                }
                MediaKind.AUDIO -> if (media.durationUs > 0) {
                    audio += Clip(clipIdFor(media.id.value), media.id, audioEnd, sourceInUs = 0, durationUs = media.durationUs)
                    audioEnd += media.durationUs
                }
            }
        }
        return Timeline(
            listOf(
                Track(VIDEO_TRACK_ID, TrackKind.VIDEO, visual),
                Track(AUDIO_TRACK_ID, TrackKind.AUDIO, audio),
            ),
        )
    }

    private fun clipIdFor(mediaId: String) = ClipId("clip-$mediaId")
}
