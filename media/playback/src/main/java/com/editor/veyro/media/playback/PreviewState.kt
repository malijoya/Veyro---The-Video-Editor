package com.editor.veyro.media.playback

import androidx.media3.common.PlaybackException

public enum class PreviewStatus {
    /** No player attached (not started yet, or released while in the background). */
    IDLE,

    /** Opening the file or buffering. */
    LOADING,
    READY,
    ENDED,
    ERROR,
}

/** Why playback failed, reduced to what the UI can explain. */
public enum class PreviewError {
    FILE_NOT_FOUND,
    NO_PERMISSION,
    UNSUPPORTED_FORMAT,

    /** The format is valid, but too large or demanding for this device's decoders (for example 8K, or 4K at 120 fps). */
    EXCEEDS_DEVICE_CAPABILITIES,
    DECODER_FAILED,
    CORRUPT_FILE,
    AUDIO_OUTPUT_FAILED,
    UNKNOWN,
    ;

    public companion object {
        /** Maps a Media3 [PlaybackException.errorCode] to a category. Pure, so it is unit-tested. */
        public fun fromPlaybackErrorCode(code: Int): PreviewError = when (code) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FILE_NOT_FOUND
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> NO_PERMISSION
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> UNSUPPORTED_FORMAT
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> EXCEEDS_DEVICE_CAPABILITIES
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> DECODER_FAILED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> CORRUPT_FILE
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> AUDIO_OUTPUT_FAILED
            else -> UNKNOWN
        }
    }
}

/** Everything the preview UI renders. Times are plan time in microseconds. */
public data class PreviewState(
    val status: PreviewStatus = PreviewStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    /** Whether a video track is playing. False for audio, so the UI shows artwork instead of a black frame. */
    val hasVideo: Boolean = false,
    val error: PreviewError? = null,
) {
    /** 0..1 progress for seek bars. */
    val progress: Float get() = if (durationUs <= 0) 0f else (positionUs.toDouble() / durationUs).toFloat().coerceIn(0f, 1f)
}
