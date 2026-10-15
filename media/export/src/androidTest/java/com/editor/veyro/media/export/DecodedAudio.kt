package com.editor.veyro.media.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The audio of an exported file, decoded back to PCM with the platform
 * decoder, so tests measure what a player would actually hear.
 *
 * Samples are kept per frame as the loudest channel, in full-scale units
 * (-1..1), indexed by the decoder's presentation times.
 */
internal class DecodedAudio(val sampleRate: Int, private val frames: FloatArray, private val startUs: Long) {

    val durationUs: Long get() = startUs + frames.size * 1_000_000L / sampleRate

    /** Highest absolute sample anywhere. */
    val peak: Float get() = frames.maxOfOrNull { abs(it) } ?: 0f

    /** How many samples sit at (or within a hair of) 16-bit full scale: hard clipping. */
    val clippedSamples: Int get() = frames.count { abs(it) >= 32_700f / 32_768f }

    /** RMS level over [[fromUs], [toUs]). */
    fun rms(fromUs: Long, toUs: Long): Float {
        val from = index(fromUs)
        val to = index(toUs)
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += frames[i] * frames[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    /** First time from [afterUs] where the 10 ms RMS passes [threshold], or null. */
    fun onsetUs(threshold: Float, afterUs: Long = 0): Long? {
        var t = afterUs
        while (t + 10_000 <= durationUs) {
            if (rms(t, t + 10_000) > threshold) return t
            t += 5_000
        }
        return null
    }

    private fun index(timeUs: Long): Int = ((timeUs - startUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames.size)

    companion object {
        /** Decodes the first audio track of [file], or null if it has none. */
        fun of(file: File): DecodedAudio? {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null.also { extractor.release() }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ArrayList<Float>()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var firstUs: Long? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (outIndex >= 0) {
                        val data = codec.getOutputBuffer(outIndex)
                        if (data != null && info.size > 0) {
                            if (firstUs == null) firstUs = info.presentationTimeUs
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            val ordered = data.order(ByteOrder.nativeOrder())
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floats = ordered.asFloatBuffer()
                                while (floats.remaining() >= channels) {
                                    var loudest = 0f
                                    repeat(channels) { val v = floats.get(); if (abs(v) > abs(loudest)) loudest = v }
                                    out += loudest
                                }
                            } else {
                                val shorts = ordered.asShortBuffer()
                                while (shorts.remaining() >= channels) {
                                    var loudest = 0f
                                    repeat(channels) { val v = shorts.get() / 32_768f; if (abs(v) > abs(loudest)) loudest = v }
                                    out += loudest
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                codec.stop()
                codec.release()
                extractor.release()
            }
            return DecodedAudio(sampleRate, out.toFloatArray(), firstUs ?: 0)
        }
    }
}
