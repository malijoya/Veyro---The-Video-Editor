package com.editor.veyro.core.model.audio

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Peak amplitudes of a media file's audio, measured from its decoded samples.
 *
 * Each bucket of [bucketUs] holds the largest absolute sample value across
 * all channels, as 0..255 of full scale. Values are rounded **up**, so a
 * stored peak is never below the real one. The mix headroom relies on that.
 *
 * At the default 20 ms buckets, an hour of audio is about 180 KB.
 */
public class Waveform(
    public val bucketUs: Long,
    public val durationUs: Long,
    private val peaks: ByteArray,
) {
    init {
        require(bucketUs > 0) { "bucketUs must be positive" }
        require(durationUs >= 0) { "durationUs must not be negative" }
    }

    public val bucketCount: Int get() = peaks.size

    /** Peak of bucket [index], 0..1. */
    public fun peakAt(index: Int): Float = (peaks[index].toInt() and 0xFF) / 255f

    /**
     * The largest peak in source time [[startUs], [endUs]). A range smaller
     * than a bucket reads the bucket it falls in. Outside the file it's 0.
     */
    public fun peakBetween(startUs: Long, endUs: Long): Float {
        if (peaks.isEmpty() || endUs <= 0 || startUs >= bucketCount * bucketUs) return 0f
        val first = (startUs.coerceAtLeast(0) / bucketUs).toInt()
        val last = ((endUs - 1).coerceAtLeast(startUs) / bucketUs).toInt().coerceAtMost(bucketCount - 1)
        var max = 0
        for (i in first..last) max = maxOf(max, peaks[i].toInt() and 0xFF)
        return max / 255f
    }

    /** Compact binary form for the disk cache: a header, then one byte per bucket. */
    public fun toBytes(): ByteArray {
        val out = ByteArray(HEADER_BYTES + peaks.size)
        writeInt(out, 0, MAGIC)
        writeInt(out, 4, FORMAT_VERSION)
        writeLong(out, 8, bucketUs)
        writeLong(out, 16, durationUs)
        writeInt(out, 24, peaks.size)
        peaks.copyInto(out, HEADER_BYTES)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Waveform && bucketUs == other.bucketUs && durationUs == other.durationUs && peaks.contentEquals(other.peaks)

    override fun hashCode(): Int = 31 * (31 * bucketUs.hashCode() + durationUs.hashCode()) + peaks.contentHashCode()

    override fun toString(): String = "Waveform(bucketUs=$bucketUs, durationUs=$durationUs, buckets=${peaks.size})"

    public companion object {
        public const val DEFAULT_BUCKET_US: Long = 20_000
        private const val MAGIC = 0x56575646 // "VWVF"
        private const val FORMAT_VERSION = 1
        private const val HEADER_BYTES = 28

        /** Reads [toBytes] output, or returns null for anything else (old format, truncated file). */
        public fun fromBytes(bytes: ByteArray): Waveform? {
            if (bytes.size < HEADER_BYTES || readInt(bytes, 0) != MAGIC || readInt(bytes, 4) != FORMAT_VERSION) return null
            val bucketUs = readLong(bytes, 8)
            val durationUs = readLong(bytes, 16)
            val count = readInt(bytes, 24)
            if (bucketUs <= 0 || durationUs < 0 || count < 0 || bytes.size != HEADER_BYTES + count) return null
            return Waveform(bucketUs, durationUs, bytes.copyOfRange(HEADER_BYTES, bytes.size))
        }

        private fun writeInt(b: ByteArray, at: Int, v: Int) {
            for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
        }

        private fun writeLong(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
        }

        private fun readInt(b: ByteArray, at: Int): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
            return v
        }

        private fun readLong(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }
    }
}

/**
 * Builds a [Waveform] from decoded PCM, fed in any chunk sizes.
 *
 * Bucket boundaries come from each chunk's presentation time, so a decoder
 * that skips or repeats data can't shift later peaks.
 */
public class WaveformBuilder(private val bucketUs: Long = Waveform.DEFAULT_BUCKET_US) {
    private var peaks = ByteArray(1_024)
    private var count = 0
    private var endUs = 0L

    /** Interleaved 16-bit samples starting at [presentationTimeUs]. */
    public fun add16Bit(samples: ShortArray, sampleCount: Int, channels: Int, sampleRate: Int, presentationTimeUs: Long) {
        addFrames(sampleCount / channels, channels, sampleRate, presentationTimeUs) { abs(samples[it].toInt()) / 32_768f }
    }

    /** Interleaved float samples (nominally -1..1) starting at [presentationTimeUs]. */
    public fun addFloat(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int, presentationTimeUs: Long) {
        addFrames(sampleCount / channels, channels, sampleRate, presentationTimeUs) { abs(samples[it]) }
    }

    private inline fun addFrames(frames: Int, channels: Int, sampleRate: Int, startUs: Long, amplitude: (Int) -> Float) {
        require(channels > 0 && sampleRate > 0) { "Invalid PCM format" }
        for (frame in 0 until frames) {
            val timeUs = startUs + frame * 1_000_000L / sampleRate
            if (timeUs < 0) continue
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, amplitude(frame * channels + c))
            record((timeUs / bucketUs).toInt(), peak)
        }
        if (frames > 0) endUs = maxOf(endUs, startUs + frames * 1_000_000L / sampleRate)
    }

    private fun record(bucket: Int, peak: Float) {
        if (bucket >= peaks.size) peaks = peaks.copyOf(maxOf(peaks.size * 2, bucket + 1))
        if (bucket >= count) count = bucket + 1
        // Round up, so the stored peak is never below the real one.
        val quantized = ceil(peak.coerceIn(0f, 1f) * 255f).toInt()
        if (quantized > (peaks[bucket].toInt() and 0xFF)) peaks[bucket] = quantized.toByte()
    }

    public fun build(): Waveform = Waveform(bucketUs, endUs, peaks.copyOf(count))
}
