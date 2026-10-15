package com.editor.veyro.media.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates small, real media files on the device, so tests exercise genuine
 * containers without binary fixtures in the repo.
 *
 * Copy of `:media:playback`'s test generator, plus marked frames for
 * measuring placement. Move the copies into a shared test-fixtures module.
 */
internal object TestMediaFiles {

    /**
     * Interleaved 16-bit samples of a sine tone: [seconds] long at
     * [amplitude] of full scale, the same on every channel.
     */
    fun tone(sampleRate: Int, channels: Int, seconds: Double, frequency: Double, amplitude: Double): ShortArray {
        val frames = (sampleRate * seconds).toInt()
        return ShortArray(frames * channels) { i ->
            val frame = i / channels
            (sin(2 * PI * frequency * frame / sampleRate) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }
    }

    /** Writes [samples] (interleaved 16-bit) as a WAV file. */
    fun wav(file: File, samples: ShortArray, sampleRate: Int, channels: Int): File {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        samples.forEach { buffer.putShort(it) }
        file.writeBytes(buffer.array())
        return file
    }

    /** 16-bit PCM WAV containing a 440 Hz tone. */
    fun wav(file: File, sampleRate: Int, channels: Int, seconds: Int): File {
        val frames = sampleRate * seconds
        val dataBytes = frames * channels * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until frames) {
            val sample = (sin(2 * PI * 440 * i / sampleRate) * Short.MAX_VALUE * 0.3).toInt().toShort()
            repeat(channels) { buffer.putShort(sample) }
        }
        file.writeBytes(buffer.array())
        return file
    }

    /**
     * A grey JPEG with a white block over its top-left quarter (a quarter of
     * the width by a quarter of the height), as stored. With
     * [exifOrientation] (an [ExifInterface] orientation), viewers must turn it
     * upright, which moves the block.
     */
    fun markedJpeg(file: File, width: Int, height: Int, exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(MARK_GREY, MARK_GREY, MARK_GREY)) }
        Canvas(bitmap).drawRect(0f, 0f, width / 4f, height / 4f, Paint().apply { color = Color.WHITE })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    fun image(file: File, width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        file.outputStream().use { bitmap.compress(format, 90, it) }
        bitmap.recycle()
        return file
    }

    /**
     * Encodes [frameCount] frames of H.264 at [fps] into an MP4 with the
     * platform encoder. Every Android device and emulator ships an AVC
     * encoder, so this works everywhere the tests run.
     */
    fun mp4(file: File, width: Int, height: Int, fps: Int, frameCount: Int, rotationDegrees: Int = 0, marked: Boolean = false): File {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotationDegrees)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var frame = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val ptsUs = frame * 1_000_000L / fps
                        if (frame == frameCount) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (marked) {
                                fillMarkedFrame(checkNotNull(codec.getInputImage(inIndex)), width, height)
                            } else {
                                fillFrame(checkNotNull(codec.getInputImage(inIndex)), width, height, frame)
                            }
                            codec.queueInputBuffer(inIndex, 0, width * height * 3 / 2, ptsUs, 0)
                            frame++
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (outIndex >= 0) {
                    val data = checkNotNull(codec.getOutputBuffer(outIndex))
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
            muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
        return file
    }

    /** Fills a flexible-YUV input image: luma changes per frame, chroma stays neutral. */
    private fun fillFrame(image: Image, width: Int, height: Int, frame: Int) {
        image.planes.forEachIndexed { index, plane ->
            val planeWidth = if (index == 0) width else width / 2
            val planeHeight = if (index == 0) height else height / 2
            val value = if (index == 0) (16 + frame * 7 % 200).toByte() else 128.toByte()
            val buffer = plane.buffer
            for (y in 0 until planeHeight) {
                for (x in 0 until planeWidth) {
                    buffer.put(y * plane.rowStride + x * plane.pixelStride, value)
                }
            }
        }
    }

    /**
     * Grey, with a white block over the top-left quarter (as stored, before
     * any rotation metadata is applied). Chroma stays neutral.
     */
    private fun fillMarkedFrame(image: Image, width: Int, height: Int) {
        image.planes.forEachIndexed { index, plane ->
            val sub = if (index == 0) 1 else 2
            val planeWidth = width / sub
            val planeHeight = height / sub
            val buffer = plane.buffer
            for (y in 0 until planeHeight) {
                for (x in 0 until planeWidth) {
                    val inBlock = x * sub < width / 4 && y * sub < height / 4
                    val value = when {
                        index != 0 -> 128
                        inBlock -> 235
                        else -> MARK_LUMA
                    }
                    buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                }
            }
        }
    }

    /** Video luma of the grey in marked frames (about RGB 120 after conversion). */
    private const val MARK_LUMA = 119

    /** RGB grey of marked images. */
    private const val MARK_GREY = 120

    private const val TIMEOUT_US = 10_000L
}
