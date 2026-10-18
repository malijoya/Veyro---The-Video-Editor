package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import com.editor.veyro.core.model.transition.Rgb
import com.editor.veyro.core.model.transition.Rgba
import com.editor.veyro.core.model.transition.Transition
import com.editor.veyro.core.model.transition.TransitionAlignment
import com.editor.veyro.core.model.transition.TransitionComposite
import com.editor.veyro.core.model.transition.TransitionRole
import com.editor.veyro.core.model.transition.TransitionType
import com.editor.veyro.core.timeline.TransitionLayout
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render plan's transitions: what the planner fits, the handle layer
 * the renderer plays under the main track, and, simulated frame by frame
 * the way the renderer composites its two layers, exactly the reference
 * picture, at several export rates, sizes and clip lengths.
 */
class TransitionPlanTest {

    private val sec = 1_000_000L
    private val rate = FrameRate.FPS_30

    private val cam = MediaRef(
        id = MediaId("cam"), uri = "content://cam", kind = MediaKind.VIDEO, displayName = "cam.mp4", mimeType = "video/mp4",
        durationUs = 10 * sec, widthPx = 1_920, heightPx = 1_080, rotationDegrees = 0, sizeBytes = null,
    )
    private val photo = MediaRef(
        id = MediaId("photo"), uri = "content://photo", kind = MediaKind.IMAGE, displayName = "photo.jpg", mimeType = "image/jpeg",
        durationUs = 0, widthPx = 4_000, heightPx = 3_000, rotationDegrees = 0, sizeBytes = null,
    )
    private val media = listOf(cam, photo)

    /** A: video from 1 s, 4 s long. B: a 3 s photo. C: video from 2 s, 4 s long. */
    private fun timeline(ab: Transition?, bc: Transition?) = Timeline(
        listOf(
            Track(
                TrackId("main"), TrackKind.VIDEO,
                listOf(
                    Clip(ClipId("A"), MediaId("cam"), 0, sec, 4 * sec, transitionOut = ab),
                    Clip(ClipId("B"), MediaId("photo"), 4 * sec, 0, 3 * sec, transitionOut = bc),
                    Clip(ClipId("C"), MediaId("cam"), 7 * sec, 2 * sec, 4 * sec),
                ),
            ),
        ),
        rate,
    )

    private fun plan(t: Timeline, fps: FrameRate = rate, width: Int = 1_280, height: Int = 720): ExportPlan =
        (ExportPlanner.plan(t, media, ExportSettings(width, height, fps)) as ExportPlanResult.Ready).plan

    private val dissolve = Transition(TransitionType.DISSOLVE, 500_000)
    private val wipe = Transition(TransitionType.WIPE_UP, sec, alignment = TransitionAlignment.AFTER_CUT)

    @Test
    fun `the plan fits transitions to their cuts on the timeline's grid, whatever the export rate`() {
        val t = timeline(dissolve, wipe)
        val expected = TransitionLayout.resolve(t) { clip -> if (clip.mediaId == MediaId("photo")) null else cam.durationUs }.map { it.window }
        for (fps in listOf(FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_30, FrameRate.FPS_60)) {
            val p = plan(t, fps)
            assertEquals(listOf(0, 1), p.transitions.map { it.outgoingIndex })
            assertEquals(expected, p.transitions.map { it.window })
            assertTrue(p.warnings.none { it == ExportWarning.TRANSITIONS_SHORTENED })
        }
        // The timeline's timing is untouched.
        assertEquals(11 * sec, plan(t).durationUs)
        assertEquals(plan(timeline(null, null)).video, plan(t).video)
    }

    @Test
    fun `transitions that don't fit are left out with a warning`() {
        // A runs to its file's end, so a centred dissolve has nothing to show after the cut.
        val t = timeline(dissolve, null).let { tl ->
            val main = tl.mainTrack!!
            tl.withTrack(main.copy(clips = listOf(main.clips[0].copy(sourceInUs = 6 * sec), main.clips[1], main.clips[2])))
        }
        val p = plan(t)
        assertTrue(p.transitions.isEmpty())
        assertTrue(ExportWarning.TRANSITIONS_SHORTENED in p.warnings)
    }

    @Test
    fun `the handle layer plays each picture's media from beyond its clip's edge`() {
        val p = plan(timeline(dissolve, wipe))
        val pieces = TransitionPlan(p).pieces
        val first = p.transitions[0].window
        val second = p.transitions[1].window
        assertEquals(3, pieces.size)
        // A dissolve centred on A|B: B (a photo) before the cut, A's media after its out-point (5 s) after it.
        assertEquals(TransitionRole.INCOMING, pieces[0].role)
        assertEquals(1, pieces[0].segmentIndex)
        assertEquals(first.cutUs, pieces[0].endUs)
        assertEquals(0, pieces[0].sourceStartUs)
        assertEquals(TransitionRole.OUTGOING, pieces[1].role)
        assertEquals(0, pieces[1].segmentIndex)
        assertEquals(first.cutUs, pieces[1].startUs)
        assertEquals(5 * sec, pieces[1].sourceStartUs)
        assertEquals(first.outgoingHandleUs, pieces[1].durationUs)
        // A wipe starting at B|C: only B runs on (a photo has no end).
        assertEquals(TransitionRole.OUTGOING, pieces[2].role)
        assertEquals(1, pieces[2].segmentIndex)
        assertEquals(second.cutUs, pieces[2].startUs)
        assertEquals(second.endUs, pieces[2].endUs)
        // In order, never overlapping, and the layer shows only there.
        pieces.zipWithNext().forEach { (x, y) -> assertTrue(x.endUs <= y.startUs) }
        val plan = TransitionPlan(p)
        assertTrue(plan.handlesVisibleAt(pieces[0].startUs))
        assertTrue(!plan.handlesVisibleAt(pieces[0].startUs - 1))
        assertTrue(!plan.handlesVisibleAt(pieces[2].endUs))
        assertTrue(!plan.handlesVisibleAt(0))
    }

    @Test
    fun `the two layers composite to the reference on every frame, at every rate and size`() {
        val types = TransitionType.entries
        for ((index, type) in types.withIndex()) {
            val alignment = TransitionAlignment.entries[index % 3]
            val t = timeline(Transition(type, 600_000, alignment = alignment), Transition(types[(index + 5) % types.size], 400_000))
            for (fps in listOf(FrameRate.FPS_24, FrameRate.FPS_30, FrameRate.FPS_60)) {
                for ((width, height) in listOf(640 to 360, 1_280 to 720, 1_080 to 1_920)) {
                    assertRendersReference(plan(t, fps, width, height), fps)
                }
            }
        }
    }

    @Test
    fun `random clip lengths and transitions composite to the reference on every frame`() {
        val random = Random(11)
        repeat(40) {
            val clips = mutableListOf<Clip>()
            var start = 0L
            repeat(random.nextInt(2, 6)) { i ->
                val still = random.nextInt(3) == 0
                val length = random.nextLong(rate.frameToUs(1), 3 * sec)
                val sourceIn = if (still) 0 else random.nextLong(0, 10 * sec - length)
                val transition = if (random.nextInt(4) == 0) {
                    null
                } else {
                    Transition(
                        TransitionType.entries.random(random),
                        random.nextLong(Transition.MIN_DURATION_US, 2 * sec),
                        alignment = TransitionAlignment.entries.random(random),
                    )
                }
                clips += Clip(ClipId("c$i"), if (still) MediaId("photo") else MediaId("cam"), start, sourceIn, length, transitionOut = transition)
                start += length
            }
            val t = Timeline(listOf(Track(TrackId("main"), TrackKind.VIDEO, clips)), rate)
            val p = plan(t, FrameRate.FPS_30, 640, 360)
            // Pieces read only media that exists.
            TransitionPlan(p).pieces.forEach { piece ->
                val segment = p.video[piece.segmentIndex]
                assertTrue(piece.sourceStartUs >= 0)
                if (segment.kind == MediaKind.VIDEO) assertTrue(piece.sourceEndUs <= cam.durationUs)
            }
            assertRendersReference(p, rate)
        }
    }

    /** Each segment's picture: a different colour field, so a wrong picture or position shows. */
    private fun picture(index: Int): (Double, Double) -> Rgb = { x, y ->
        val base = 0.1 + 0.15 * (index % 5)
        Rgb(base + 0.3 * x, 0.2 + 0.1 * (index % 3) + 0.4 * y, 0.9 - base * x)
    }

    /**
     * Simulates the renderer at every frame of [fps]: the main track's
     * segment at t on top, drawn through [TransitionPlan.mainLook], over the
     * handle piece at t (if any) drawn through [TransitionPlan.pieceLook];
     * and checks it against [TransitionComposite.frame] (or the plain
     * segment outside transitions).
     */
    private fun assertRendersReference(p: ExportPlan, fps: FrameRate) {
        val plan = TransitionPlan(p)
        val starts = p.segmentStarts()
        val width = p.settings.widthPx
        val height = p.settings.heightPx
        val points = listOf(0.11 to 0.13, 0.37 to 0.71, 0.52 to 0.48, 0.83 to 0.29, 0.96 to 0.94)
        var frame = 0L
        while (fps.frameToUs(frame) < p.durationUs) {
            val t = fps.frameToUs(frame++)
            val segment = starts.indexOfLast { it <= t }
            val piece = plan.pieces.firstOrNull { t in it }
            val transition = p.transitions.firstOrNull { t >= it.window.startUs && t < it.window.endUs }
            for ((x, y) in points) {
                val top = TransitionComposite.layer(plan.mainLook(segment, t, width, height), picture(segment), x, y)
                val bottom = piece?.let { TransitionComposite.layer(plan.pieceLook(it, t, width, height), picture(it.segmentIndex), x, y) } ?: Rgba(0.0, 0.0, 0.0, 0.0)
                val rendered = TransitionComposite.blend(top, bottom)
                val expected = if (transition == null) {
                    picture(segment)(x, y)
                } else {
                    TransitionComposite.frame(transition.window, picture(transition.outgoingIndex), picture(transition.incomingIndex), t, x, y, width, height)
                }
                val message = "t=$t fps=$fps ${width}x$height at $x,$y"
                assertEquals(message, expected.r, rendered.r, 1e-9)
                assertEquals(message, expected.g, rendered.g, 1e-9)
                assertEquals(message, expected.b, rendered.b, 1e-9)
            }
        }
    }
}
