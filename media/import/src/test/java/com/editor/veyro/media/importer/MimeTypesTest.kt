package com.editor.veyro.media.importer

import com.editor.veyro.core.model.project.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTypesTest {
    @Test
    fun `classifies supported media`() {
        assertEquals(MediaKind.VIDEO, MimeTypes.kindOf("video/mp4"))
        assertEquals(MediaKind.VIDEO, MimeTypes.kindOf("VIDEO/QuickTime"))
        assertEquals(MediaKind.IMAGE, MimeTypes.kindOf("image/heic"))
        assertEquals(MediaKind.AUDIO, MimeTypes.kindOf("audio/mpeg"))
        assertEquals(MediaKind.AUDIO, MimeTypes.kindOf("audio/x-wav; codecs=1"))
        assertEquals(MediaKind.AUDIO, MimeTypes.kindOf("application/ogg"))
    }

    @Test
    fun `rejects unsupported types`() {
        assertNull(MimeTypes.kindOf(null))
        assertNull(MimeTypes.kindOf("application/pdf"))
        assertNull(MimeTypes.kindOf("text/plain"))
        assertNull(MimeTypes.kindOf("image/svg+xml"))
        assertNull(MimeTypes.kindOf("application/octet-stream"))
    }

    @Test
    fun `guesses type from file name`() {
        assertEquals("video/mp4", MimeTypes.fromFileName("Clip.MP4"))
        assertEquals("audio/mpeg", MimeTypes.fromFileName("song.mp3"))
        assertEquals("audio/x-wav", MimeTypes.fromFileName("take 2.wav"))
        assertEquals("image/jpeg", MimeTypes.fromFileName("IMG_0001.jpeg"))
        assertNull(MimeTypes.fromFileName("README"))
        assertNull(MimeTypes.fromFileName("archive.zip"))
        assertNull(MimeTypes.fromFileName(null))
    }
}
