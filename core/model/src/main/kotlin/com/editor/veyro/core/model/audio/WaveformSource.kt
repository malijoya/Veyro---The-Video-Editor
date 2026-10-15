package com.editor.veyro.core.model.audio

import com.editor.veyro.core.model.project.MediaRef

/**
 * Measured waveforms of project media. The app's implementation decodes the
 * audio once and caches the peaks on disk.
 *
 * There is no fallback: when a file has no audio stream or can't be decoded,
 * there is no waveform, and callers show none rather than a made-up one.
 */
public interface WaveformSource {
    /** A waveform already in memory, or null. Never blocks. */
    public fun cached(media: MediaRef): Waveform?

    /** Analyses [media] if needed. Null when it has no audio, or it can't be read. */
    public suspend fun load(media: MediaRef): Waveform?

    public companion object {
        /** Knows no waveforms. For tests and previews. */
        public val NONE: WaveformSource = object : WaveformSource {
            override fun cached(media: MediaRef): Waveform? = null
            override suspend fun load(media: MediaRef): Waveform? = null
        }
    }
}
