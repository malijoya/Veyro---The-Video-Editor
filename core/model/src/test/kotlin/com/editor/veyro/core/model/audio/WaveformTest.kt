package com.editor.veyro.core.model.audio

import com.editor.veyro.core.model.project.MediaId
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import com.editor.veyro.core.model.project.Project
import com.editor.veyro.core.model.project.ProjectId
import com.editor.veyro.core.model.timeline.DefaultArrangement
import com.editor.veyro.core.model.timeline.Timeline
import com.editor.veyro.core.model.timeline.Track
import com.editor.veyro.core.model.timeline.TrackId
import com.editor.veyro.core.model.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {

    @Test
    fun `peaks are measured per bucket from the loudest sample on any channel`() {
        val builder = WaveformBuilder(bucketUs = 10_000) // 10 ms
        // 1 kHz sample rate, stereo: 10 frames per bucket. Bucket 0 quiet, bucket 1 loud on the right channel only.
        val samples = ShortArray(40) { 0 }
        samples[3] = 1_000 // Bucket 0, left.
        samples[20 + 7] = (-16_384).toShort() // Bucket 1, right: half scale.
        builder.add16Bit(samples, samples.size, channels = 2, sampleRate = 1_000, presentationTimeUs = 0)
        val waveform = builder.build()
        assertEquals(2, waveform.bucketCount)
        assertEquals(20_000L, waveform.durationUs)
        assertTrue(waveform.peakAt(0) >= 1_000 / 32_768f)
        assertEquals(0.5f, waveform.peakAt(1), 1 / 255f)
    }

    @Test
    fun `stored peaks are never below the real ones`() {
        val builder = WaveformBuilder(bucketUs = 10_000)
        val values = floatArrayOf(0.001f, 0.3337f, 0.7f, 0.9999f)
        values.forEachIndexed { i, v ->
            builder.addFloat(floatArrayOf(v), 1, channels = 1, sampleRate = 100, presentationTimeUs = i * 10_000L)
        }
        val waveform = builder.build()
        values.forEachIndexed { i, v -> assertTrue("bucket $i", waveform.peakAt(i) >= v) }
    }

    @Test
    fun `buckets follow presentation time, not sample count`() {
        val builder = WaveformBuilder(bucketUs = 10_000)
        // A chunk at 0 and the next one jumping to 100 ms (a decoder gap).
        builder.addFloat(floatArrayOf(0.5f), 1, 1, 100, presentationTimeUs = 0)
        builder.addFloat(floatArrayOf(0.8f), 1, 1, 100, presentationTimeUs = 100_000)
        val waveform = builder.build()
        assertEquals(11, waveform.bucketCount)
        assertEquals(0f, waveform.peakAt(5), 0f)
        assertTrue(waveform.peakAt(10) >= 0.8f)
    }

    @Test
    fun `peak between reads the loudest bucket in a time range`() {
        val builder = WaveformBuilder(bucketUs = 10_000)
        floatArrayOf(0.1f, 0.6f, 0.2f, 0.9f).forEachIndexed { i, v -> builder.addFloat(floatArrayOf(v), 1, 1, 100, i * 10_000L) }
        val waveform = builder.build()
        assertTrue(waveform.peakBetween(0, 20_000) >= 0.6f)
        assertTrue(waveform.peakBetween(0, 20_000) < 0.61f)
        assertTrue(waveform.peakBetween(15_000, 16_000) >= 0.6f) // Inside one bucket.
        assertEquals(0f, waveform.peakBetween(1_000_000, 2_000_000), 0f) // Past the end.
    }

    @Test
    fun `the cache format round-trips and rejects anything else`() {
        val builder = WaveformBuilder()
        builder.addFloat(FloatArray(4_800) { (it % 100) / 100f }, 4_800, 1, 48_000, 0)
        val waveform = builder.build()
        val bytes = waveform.toBytes()
        assertEquals(waveform, Waveform.fromBytes(bytes))
        assertNull(Waveform.fromBytes(bytes.copyOf(bytes.size - 1)))
        assertNull(Waveform.fromBytes(ByteArray(40)))
        assertNull(Waveform.fromBytes(ByteArray(3)))
    }

    // ---------------- Standard audio tracks ----------------

    private fun media(id: String, kind: MediaKind, durationUs: Long = 2_000_000) = MediaRef(
        id = MediaId(id), uri = "content://$id", kind = kind, displayName = null, mimeType = null,
        durationUs = durationUs, widthPx = 0, heightPx = 0, rotationDegrees = 0, sizeBytes = null,
    )

    @Test
    fun `new timelines have music and voiceover tracks, and audio goes to music`() {
        val timeline = DefaultArrangement.build(Project(ProjectId("p"), "P", 0, 0, listOf(media("song", MediaKind.AUDIO))))
        val music = timeline.tracks.single { it.role == AudioRole.MUSIC }
        val voice = timeline.tracks.single { it.role == AudioRole.VOICEOVER }
        assertEquals(1, music.clips.size)
        assertTrue(voice.clips.isEmpty())
        assertEquals(listOf(TrackKind.VIDEO, TrackKind.AUDIO, TrackKind.AUDIO), timeline.tracks.map { it.kind })
    }

    @Test
    fun `timelines saved before roles get them without moving clips`() {
        val old = Timeline(
            listOf(
                Track(TrackId("video-main"), TrackKind.VIDEO, emptyList()),
                Track(TrackId("audio-1"), TrackKind.AUDIO, emptyList()),
            ),
        )
        val upgraded = DefaultArrangement.withStandardTracks(old)
        assertEquals(AudioRole.MUSIC, upgraded.track(TrackId("audio-1"))!!.role)
        assertEquals(AudioRole.VOICEOVER, upgraded.tracks.last().role)
        assertEquals(3, upgraded.tracks.size)
        // Already standard: unchanged.
        assertTrue(DefaultArrangement.withStandardTracks(upgraded) === upgraded)
    }
}
