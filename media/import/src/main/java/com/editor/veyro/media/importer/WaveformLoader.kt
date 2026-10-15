package com.editor.veyro.media.importer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import com.editor.veyro.core.model.audio.Waveform
import com.editor.veyro.core.model.audio.WaveformBuilder
import com.editor.veyro.core.model.audio.WaveformSource
import com.editor.veyro.core.model.project.MediaKind
import com.editor.veyro.core.model.project.MediaRef
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Real waveforms: decodes a file's first audio stream with `MediaExtractor`
 * and `MediaCodec`, and records the peak of every 20 ms ([WaveformBuilder]).
 *
 * - Results are kept in memory (LRU) and on disk under `cacheDir/waveforms`,
 *   keyed by URI, size and duration, so a replaced file is analysed again.
 *   The system may clear the disk cache; it's rebuilt on demand.
 * - Files without an audio stream (photos, silent videos) and files that
 *   fail to decode get no waveform, and that "no" is remembered for the
 *   session so they aren't decoded again.
 * - At most [MAX_PARALLEL_DECODES] files decode at once; concurrent requests
 *   for the same file share one decode.
 */
public class WaveformLoader(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val cacheDir: File = File(context.applicationContext.cacheDir, "waveforms"),
) : WaveformSource {

    private val appContext = context.applicationContext
    private val memory = LruCache<String, Waveform>(MEMORY_ENTRIES)
    private val missing = HashSet<String>()
    private val inFlight = HashMap<String, Deferred<Waveform?>>()
    private val lock = Any()
    private val decodes = Semaphore(MAX_PARALLEL_DECODES)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override fun cached(media: MediaRef): Waveform? = memory.get(keyOf(media))

    override suspend fun load(media: MediaRef): Waveform? {
        if (!media.hasAudio || media.kind == MediaKind.IMAGE) return null
        val key = keyOf(media)
        memory.get(key)?.let { return it }
        val job = synchronized(lock) {
            if (key in missing) return null
            inFlight.getOrPut(key) {
                // Shared by everyone waiting for this file. It isn't tied to one caller, so one caller cancelling doesn't stop the others.
                scope.async { loadOrDecode(media, key) }.also { deferred ->
                    deferred.invokeOnCompletion { synchronized(lock) { inFlight.remove(key) } }
                }
            }
        }
        return job.await()
    }

    private suspend fun loadOrDecode(media: MediaRef, key: String): Waveform? {
        val file = File(cacheDir, "$key.wf")
        val fromDisk = runCatching { if (file.isFile) Waveform.fromBytes(file.readBytes()) else null }.getOrNull()
        if (fromDisk != null) {
            memory.put(key, fromDisk)
            return fromDisk
        }
        val decoded = decodes.withPermit { decode(Uri.parse(media.uri)) }
        if (decoded == null) {
            synchronized(lock) { missing += key }
            return null
        }
        memory.put(key, decoded)
        runCatching {
            cacheDir.mkdirs()
            val temp = File(cacheDir, "$key.tmp")
            temp.writeBytes(decoded.toBytes())
            temp.renameTo(file)
        }
        return decoded
    }

    /** Decodes every sample of the first audio stream. Null if there is none or decoding fails. */
    private suspend fun decode(uri: Uri): Waveform? = withContext(ioDispatcher) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val builder = WaveformBuilder()
            var outputFormat = decoder.outputFormat
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                    outIndex >= 0 -> {
                        val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        val data = decoder.getOutputBuffer(outIndex)
                        if (data != null && info.size > 0) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            val ordered = data.order(ByteOrder.nativeOrder())
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val count = info.size / 4
                                if (floats.size < count) floats = FloatArray(count)
                                ordered.asFloatBuffer().get(floats, 0, count)
                                builder.addFloat(floats, count, channels, sampleRate, info.presentationTimeUs)
                            } else {
                                val count = info.size / 2
                                if (shorts.size < count) shorts = ShortArray(count)
                                ordered.asShortBuffer().get(shorts, 0, count)
                                builder.add16Bit(shorts, count, channels, sampleRate, info.presentationTimeUs)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            builder.build().takeIf { it.bucketCount > 0 }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable or undecodable: no waveform, never a made-up one.
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun keyOf(media: MediaRef): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${media.uri}|${media.sizeBytes}|${media.durationUs}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MEMORY_ENTRIES = 64
        const val MAX_PARALLEL_DECODES = 2
        const val TIMEOUT_US = 10_000L
    }
}
