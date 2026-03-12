package com.danielealbano.androidremotecontrolmcp.services.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MimeTypeUtils")
class MimeTypeUtilsTest {
    @Test
    fun `guessMimeType returns correct type for known extensions`() {
        assertEquals("image/jpeg", MimeTypeUtils.guessMimeType("photo.jpg"))
        assertEquals("image/png", MimeTypeUtils.guessMimeType("image.png"))
        assertEquals("application/pdf", MimeTypeUtils.guessMimeType("document.pdf"))
        assertEquals("application/json", MimeTypeUtils.guessMimeType("data.json"))
    }

    @Test
    fun `guessMimeType returns octet-stream for unknown extension`() {
        assertEquals("application/octet-stream", MimeTypeUtils.guessMimeType("file.xyz"))
    }

    @Test
    fun `guessMimeType handles no extension`() {
        assertEquals("application/octet-stream", MimeTypeUtils.guessMimeType("file"))
    }

    @Test
    fun `guessMimeType is case insensitive`() {
        assertEquals("image/jpeg", MimeTypeUtils.guessMimeType("photo.JPG"))
    }
}
