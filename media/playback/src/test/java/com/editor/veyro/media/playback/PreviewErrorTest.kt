package com.editor.veyro.media.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewErrorTest {
    @Test
    fun `media3 error codes map to user-facing categories`() {
        fun map(code: Int) = PreviewError.fromPlaybackErrorCode(code)
        assertEquals(PreviewError.FILE_NOT_FOUND, map(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PreviewError.NO_PERMISSION, map(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertEquals(PreviewError.UNSUPPORTED_FORMAT, map(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PreviewError.UNSUPPORTED_FORMAT, map(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(
            PreviewError.EXCEEDS_DEVICE_CAPABILITIES,
            map(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES),
        )
        assertEquals(PreviewError.DECODER_FAILED, map(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PreviewError.DECODER_FAILED, map(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertEquals(PreviewError.CORRUPT_FILE, map(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(PreviewError.AUDIO_OUTPUT_FAILED, map(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(PreviewError.UNKNOWN, map(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(PreviewError.UNKNOWN, map(-12345))
    }
}
