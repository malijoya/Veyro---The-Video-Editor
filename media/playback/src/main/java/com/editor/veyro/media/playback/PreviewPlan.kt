package com.editor.veyro.media.playback

import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.timeline.export.ExportPlan

/**
 * One stretch of a source file played back in preview.
 *
 * @property sourceStartUs where playback starts inside the source file.
 * @property durationUs how long this segment lasts on the plan's timeline.
 * @property clipped whether playback must stop at `sourceStartUs + durationUs`.
 *   False for a whole asset, which plays to the file's real end, even if the
 *   probed duration was a millisecond short.
 * @property still true for a photo. It is held for [durationUs] and drawn by
 *   the UI over the video surface, because the preview surface renders video
 *   only.
 */
public data class PreviewSegment(
    val uri: String,
    val sourceStartUs: Long,
    val durationUs: Long,
    val clipped: Boolean,
    val still: Boolean = false,
) {
    init {
        require(sourceStartUs >= 0) { "sourceStartUs must not be negative" }
        require(durationUs > 0) { "durationUs must be positive" }
    }
}

/**
 * What the preview plays, with time in microseconds. Two forms:
 * - [segments] back to back on a single track, played as a playlist. Used
 *   for one whole asset ([forAsset]).
 * - A whole timeline ([render], from [forTimeline]): the same render plan
 *   export uses, played as a Media3 composition, so every track is mixed
 *   with its volume, fades and mute. [segments] is then a single segment
 *   spanning the timeline, which keeps the position math uniform.
 */
public class PreviewPlan(public val segments: List<PreviewSegment>, public val render: ExportPlan? = null) {
    init {
        require(segments.isNotEmpty()) { "A preview plan needs at least one segment" }
    }

    private val starts: LongArray = LongArray(segments.size).also { array ->
        var offset = 0L
        segments.forEachIndexed { index, segment ->
            array[index] = offset
            offset = Math.addExact(offset, segment.durationUs)
        }
    }

    public val durationUs: Long = starts.last() + segments.last().durationUs

    /** Where segment [index] starts on the plan's timeline. */
    public fun startOfSegmentUs(index: Int): Long = starts[index]

    /** Plan time for a position inside segment [index]. Clamped to that segment. */
    public fun planTimeUs(index: Int, offsetInSegmentUs: Long): Long {
        val segment = segments[index]
        return starts[index] + offsetInSegmentUs.coerceIn(0, segment.durationUs)
    }

    /**
     * The segment showing at [planTimeUs], and the offset inside it.
     * - Times before 0 map to the start.
     * - Times at or past the end map to the end of the last segment.
     * - A time exactly on a boundary belongs to the following segment.
     */
    public fun locate(planTimeUs: Long): Location {
        if (planTimeUs >= durationUs) return Location(segments.lastIndex, segments.last().durationUs)
        val time = planTimeUs.coerceAtLeast(0)
        var index = starts.binarySearch(time)
        if (index < 0) index = -index - 2 // Insertion point minus one: the segment containing `time`.
        return Location(index, time - starts[index])
    }

    public data class Location(val segmentIndex: Int, val offsetUs: Long)

    override fun equals(other: Any?): Boolean = other is PreviewPlan && other.segments == segments && other.render == render
    override fun hashCode(): Int = 31 * segments.hashCode() + render.hashCode()
    override fun toString(): String = if (render != null) "PreviewPlan(timeline ${render.durationUs} us)" else "PreviewPlan($segments)"

    public companion object {
        /** Plays a whole timeline, every track mixed. Needs an engine that supports [PlaybackEngine.loadTimeline]. */
        public fun forTimeline(render: ExportPlan): PreviewPlan =
            PreviewPlan(listOf(PreviewSegment(TIMELINE_URI, sourceStartUs = 0, durationUs = render.durationUs, clipped = true)), render)

        /** Placeholder URI of the single segment of a [forTimeline] plan. Never opened. */
        internal const val TIMELINE_URI: String = "veyro:timeline"

        /**
         * Plays one imported video or audio file from start to end. Images
         * have no playback until they become timeline clips with a duration.
         */
        public fun forAsset(media: MediaRef): PreviewPlan {
            require(media.kind != MediaKind.IMAGE) { "Images are shown, not played" }
            return PreviewPlan(listOf(PreviewSegment(media.uri, sourceStartUs = 0, durationUs = media.durationUs, clipped = false)))
        }
    }
}
