package com.editor.veyro.core.timeline.export

import com.editor.veyro.core.model.audio.AudioRole
import com.editor.veyro.core.model.overlay.Overlay
import com.editor.veyro.core.model.overlay.OverlayContent
import com.editor.veyro.core.model.overlay.OverlayTransform
import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import com.editor.veyro.core.model.timeline.Clip
import com.editor.veyro.core.model.timeline.ClipEffect
import com.editor.veyro.core.model.timeline.ClipId
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the render plan carries text and stickers. */
class OverlayPlanTest {

    private val sec = 1_000_000L
    private val settings = ExportSettings(1_080, 1_920, FrameRate.FPS_30)

    private fun ref(id: String, kind: MediaKind, width: Int = 400, height: Int = 200, rotation: Int = 0) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = "$id.file", mimeType = null,
        durationUs = if (kind == MediaKind.IMAGE) 0 else 10 * sec, widthPx = width, heightPx = height,
        rotationDegrees = rotation, sizeBytes = null,
    )

    private val media = listOf(
        ref("cam", MediaKind.VIDEO, 1_920, 1_080),
        ref("logo", MediaKind.IMAGE, rotation = 90),
        ref("song", MediaKind.AUDIO, 0, 0),
    )

    private fun text(id: String, words: String, start: Long, duration: Long, opacity: Float = 1f, effects: List<ClipEffect> = emptyList()) =
        Clip(ClipId(id), null, start, 0, duration, effects = effects, overlay = Overlay(OverlayContent.Text(words), OverlayTransform(opacity = opacity)))

    private fun sticker(id: String, media: String, start: Long, duration: Long) =
        Clip(ClipId(id), MediaId(media), start, 0, duration, overlay = Overlay(OverlayContent.Sticker))

    /** Overlay lanes listed top first, then a 10 s main track. */
    private fun timeline(vararg lanes: List<Clip>, mainClips: List<Clip> = listOf(Clip(ClipId("V"), MediaId("cam"), 0, 0, 10 * sec))) = Timeline(
        lanes.mapIndexed { i, clips -> Track(TrackId("overlay-${i + 1}"), TrackKind.OVERLAY, clips) } +
            Track(TrackId("main"), TrackKind.VIDEO, mainClips) +
            Track(TrackId("music"), TrackKind.AUDIO, emptyList(), role = AudioRole.MUSIC),
        FrameRate.FPS_30,
    )

    private fun ready(result: ExportPlanResult): ExportPlan {
        assertTrue("Expected a plan, got $result", result is ExportPlanResult.Ready)
        return (result as ExportPlanResult.Ready).plan
    }

    @Test
    fun `overlays are listed bottom layer first, at their timeline positions`() {
        val plan = ready(
            ExportPlanner.plan(
                timeline(
                    listOf(text("top", "Title", 1 * sec, 2 * sec)),
                    listOf(sticker("mid", "logo", 0, 4 * sec), text("later", "Bye", 8 * sec, 1 * sec)),
                    listOf(text("bottom", "Under", 2 * sec, 3 * sec)),
                ),
                media, settings,
            ),
        )
        assertEquals(listOf("bottom", "mid", "later", "top"), plan.overlays.map { it.clipId })
        val top = plan.overlays.last()
        assertEquals(1 * sec, top.startUs)
        assertEquals(3 * sec, top.endUs)
        assertTrue(2 * sec in top)
        assertFalse(3 * sec in top) // End is exclusive.
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `stickers carry their image and its upright size`() {
        val plan = ready(ExportPlanner.plan(timeline(listOf(sticker("s", "logo", 0, 1 * sec))), media, settings))
        val layer = plan.overlays.single()
        assertEquals("content://logo", layer.imageUri)
        // Stored 400 × 200 and turned 90°: upright it is 200 × 400.
        assertEquals(200, layer.imageWidthPx)
        assertEquals(400, layer.imageHeightPx)
        assertEquals(90, layer.imageRotationDegrees)
    }

    @Test
    fun `overlays past the end of the picture are cut there, or left out`() {
        val plan = ready(
            ExportPlanner.plan(
                timeline(listOf(text("cut", "Cut", 9 * sec, 3 * sec), text("gone", "Gone", 12 * sec, 1 * sec))),
                media, settings,
            ),
        )
        assertEquals(listOf("cut"), plan.overlays.map { it.clipId })
        assertEquals(10 * sec, plan.overlays.single().endUs)
        assertTrue(ExportWarning.OVERLAYS_TRIMMED_TO_VIDEO in plan.warnings)
    }

    @Test
    fun `empty or invisible text is skipped, effects on overlays are reported`() {
        val plan = ready(
            ExportPlanner.plan(
                timeline(
                    listOf(
                        text("blank", "   ", 0, 1 * sec),
                        text("hidden", "Hidden", 1 * sec, 1 * sec, opacity = 0f),
                        text("fx", "Fx", 2 * sec, 1 * sec, effects = listOf(ClipEffect("e", "blur"))),
                    ),
                ),
                media, settings,
            ),
        )
        assertEquals(listOf("fx"), plan.overlays.map { it.clipId })
        assertTrue(ExportWarning.EFFECTS_NOT_RENDERED in plan.warnings)
        assertFalse(ExportWarning.EXTRA_TRACKS_IGNORED in plan.warnings) // Overlay lanes aren't extra video tracks.
    }

    @Test
    fun `a sticker whose image is missing blocks the export like any missing file`() {
        val blocked = ExportPlanner.plan(timeline(listOf(sticker("s", "logo", 0, 1 * sec))), media, settings, unavailable = setOf(MediaId("logo")))
        assertEquals(ExportPlanResult.Blocked(setOf(ExportBlocker.MEDIA_UNAVAILABLE), listOf("logo.file")), blocked)

        val notAnImage = ExportPlanner.plan(timeline(listOf(sticker("s", "song", 0, 1 * sec))), media, settings)
        assertTrue(notAnImage is ExportPlanResult.Blocked)
    }

    @Test
    fun `an audio-only preview has nothing to draw overlays on`() {
        val songOnly = Timeline(
            listOf(
                Track(TrackId("overlay-1"), TrackKind.OVERLAY, listOf(text("t", "Hi", 0, 1 * sec))),
                Track(TrackId("main"), TrackKind.VIDEO, emptyList()),
                Track(TrackId("music"), TrackKind.AUDIO, listOf(Clip(ClipId("M"), MediaId("song"), 0, 0, 5 * sec)), role = AudioRole.MUSIC),
            ),
        )
        val songRef = ref("song", MediaKind.AUDIO, 0, 0).copy(audio = com.editor.veyro.core.model.project.AudioTrackInfo("audio/mpeg", 44_100, 2, null))
        val plan = ready(ExportPlanner.plan(songOnly, listOf(songRef), settings, requireVideo = false))
        assertTrue(plan.overlays.isEmpty())
        // And a plan can't be built with overlays but no picture.
        assertThrows(IllegalArgumentException::class.java) {
            plan.copy(overlays = listOf(OverlayLayer("t", 0, 1 * sec, Overlay(OverlayContent.Text("Hi")))))
        }
    }

    @Test
    fun `layers must be consistent`() {
        assertThrows(IllegalArgumentException::class.java) { OverlayLayer("x", 2, 1, Overlay(OverlayContent.Text("Hi"))) }
        assertThrows(IllegalArgumentException::class.java) { OverlayLayer("x", 0, 1, Overlay(OverlayContent.Sticker)) }
        assertThrows(IllegalArgumentException::class.java) { OverlayLayer("x", 0, 1, Overlay(OverlayContent.Text("Hi")), imageUri = "content://a") }
    }
}
