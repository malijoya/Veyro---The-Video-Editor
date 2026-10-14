package com.editor.veyro.media.importer

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.time.FrameRate
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Probes real files generated on the device: video, image and audio, plus the
 * unsupported, missing and corrupt cases. `file://` URIs exercise the same
 * code paths as picker `content://` URIs (open, MIME detection, extractor,
 * retriever), apart from the provider query.
 */
@RunWith(AndroidJUnit4::class)
class MediaImportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "media-import-test").apply { mkdirs() }
    private val prober = MediaProber(context, Dispatchers.IO)
    private val access = MediaAccess(context, Dispatchers.IO)
    private val thumbnails = ThumbnailLoader(context, Dispatchers.IO)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun probe(file: File): ProbeResult = runBlocking { prober.probe(Uri.fromFile(file)) }
    private fun success(file: File): MediaRef {
        val result = probe(file)
        assertTrue("Expected success for ${file.name} but got $result", result is ProbeResult.Success)
        return (result as ProbeResult.Success).media
    }

    private fun assertDurationNear(expectedUs: Long, actualUs: Long, toleranceUs: Long = 100_000) {
        assertTrue("duration $actualUs not within $toleranceUs of $expectedUs", abs(expectedUs - actualUs) <= toleranceUs)
    }

    @Test
    fun videoImport_readsDimensionsDurationCodecAndFrameRate() {
        val file = TestMediaFiles.mp4(File(dir, "clip.mp4"), width = 320, height = 240, fps = 30, frameCount = 60)
        val media = success(file)
        assertEquals(MediaKind.VIDEO, media.kind)
        assertEquals("video/mp4", media.mimeType)
        assertEquals(320, media.widthPx)
        assertEquals(240, media.heightPx)
        assertEquals(0, media.rotationDegrees)
        assertDurationNear(2_000_000, media.durationUs)
        assertEquals("video/avc", media.video?.codecMime)
        // MP4 files written by MediaMuxer may omit the declared rate. When it is
        // derived from the frame count (API 28+), it must snap to 30.
        media.video?.frameRate?.let { assertEquals(FrameRate.FPS_30, it) }
        assertNull("Encoded clip has no sound track", media.audio)
        assertEquals("clip.mp4", media.displayName)
        assertEquals(file.length(), media.sizeBytes)
    }

    @Test
    fun audioImport_readsDurationSampleRateAndChannels() {
        val file = TestMediaFiles.wav(File(dir, "tone.wav"), sampleRate = 44_100, channels = 2, seconds = 2)
        val media = success(file)
        assertEquals(MediaKind.AUDIO, media.kind)
        assertDurationNear(2_000_000, media.durationUs)
        assertEquals(0, media.widthPx)
        assertEquals(44_100, media.audio?.sampleRateHz)
        assertEquals(2, media.audio?.channelCount)
        assertTrue(media.hasAudio)
    }

    @Test
    fun imageImport_readsDimensionsAndMime() {
        val media = success(TestMediaFiles.image(File(dir, "still.png"), 640, 360, Bitmap.CompressFormat.PNG))
        assertEquals(MediaKind.IMAGE, media.kind)
        assertEquals(640, media.widthPx)
        assertEquals(360, media.heightPx)
        assertEquals(0L, media.durationUs)
        assertEquals("image/png", media.mimeType)
    }

    @Test
    fun imageImport_appliesExifOrientation() {
        val file = TestMediaFiles.image(
            File(dir, "portrait.jpg"), 400, 300, Bitmap.CompressFormat.JPEG,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90,
        )
        val media = success(file)
        assertEquals(90, media.rotationDegrees)
        assertEquals(300, media.displayWidthPx)
        assertEquals(400, media.displayHeightPx)
    }

    @Test
    fun unsupportedFile_isRejected() {
        val file = File(dir, "notes.txt").apply { writeText("not media") }
        assertEquals(ProbeFailure.UNSUPPORTED_TYPE, (probe(file) as ProbeResult.Failure).reason)
    }

    @Test
    fun missingFile_isNotAccessible() {
        val result = probe(File(dir, "deleted.mp4"))
        assertEquals(ProbeFailure.NOT_ACCESSIBLE, (result as ProbeResult.Failure).reason)
    }

    @Test
    fun corruptFiles_areUnreadable() {
        assertEquals(ProbeFailure.UNREADABLE, (probe(TestMediaFiles.garbage(File(dir, "broken.mp4"))) as ProbeResult.Failure).reason)
        assertEquals(ProbeFailure.UNREADABLE, (probe(TestMediaFiles.garbage(File(dir, "broken.mp3"))) as ProbeResult.Failure).reason)
        assertEquals(ProbeFailure.UNREADABLE, (probe(TestMediaFiles.garbage(File(dir, "broken.png"))) as ProbeResult.Failure).reason)
    }

    @Test
    fun availability_tracksFileDeletion() = runBlocking {
        val file = TestMediaFiles.wav(File(dir, "gone.wav"), 8_000, 1, 1)
        val uri = Uri.fromFile(file)
        assertTrue(access.isAvailable(uri))
        file.delete()
        assertFalse(access.isAvailable(uri))
    }

    @Test
    fun thumbnails_existForPicturesAndNotForPlainAudio() = runBlocking {
        val png = TestMediaFiles.image(File(dir, "thumb.png"), 800, 600, Bitmap.CompressFormat.PNG)
        val bitmap = thumbnails.load(Uri.fromFile(png).toString(), MediaKind.IMAGE, 128)
        assertNotNull(bitmap)
        assertTrue(maxOf(bitmap!!.width, bitmap.height) in 128..800)

        val video = TestMediaFiles.mp4(File(dir, "thumb.mp4"), 320, 240, 30, 10)
        assertNotNull(thumbnails.load(Uri.fromFile(video).toString(), MediaKind.VIDEO, 128))

        val wav = TestMediaFiles.wav(File(dir, "thumb.wav"), 8_000, 1, 1)
        assertNull("WAV has no cover art", thumbnails.load(Uri.fromFile(wav).toString(), MediaKind.AUDIO, 128))
    }
}
