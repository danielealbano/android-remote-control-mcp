package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.integration.FakeOAuthHttpFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

@DisplayName("ConsentLogo")
class ConsentLogoTest {
    @Test
    @DisplayName("imageType tells PNG, JPEG, GIF and WebP by their first bytes")
    fun imageTypes() {
        assertEquals("image/png", ConsentLogo.imageType(PNG))
        assertEquals("image/jpeg", ConsentLogo.imageType(byteArrayOf(-1, -40, -1, 0)))
        assertEquals("image/gif", ConsentLogo.imageType("GIF89a...".encodeToByteArray()))
        assertEquals("image/gif", ConsentLogo.imageType("GIF87a...".encodeToByteArray()))
        assertEquals("image/webp", ConsentLogo.imageType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
        assertNull(ConsentLogo.imageType("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()))
        assertNull(ConsentLogo.imageType("RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray()))
        assertNull(ConsentLogo.imageType(PNG.copyOf(4)))
        assertNull(ConsentLogo.imageType(ByteArray(0)))
    }

    @Test
    @DisplayName("embed returns the logo as a data URI typed by its bytes, not by the server")
    fun embedsAsDataUri() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(LOGO to FetchedBody("text/html", PNG)))
            assertEquals(
                "data:image/png;base64,${Base64.getEncoder().encodeToString(PNG)}",
                ConsentLogo.embed(fetcher, LOGO),
            )
        }

    @Test
    @DisplayName("embed gives nothing for no logo, a failed download, a non-image or an oversized one")
    fun embedRefusals() =
        runTest {
            assertNull(ConsentLogo.embed(FakeOAuthHttpFetcher(), null))
            assertNull(ConsentLogo.embed(FakeOAuthHttpFetcher(), LOGO))
            val svg = FetchedBody("image/svg+xml", "<svg/>".encodeToByteArray())
            assertNull(ConsentLogo.embed(FakeOAuthHttpFetcher(mapOf(LOGO to svg)), LOGO))
            val huge = PNG + ByteArray(OAuthPolicy.MAX_LOGO_BYTES)
            assertNull(ConsentLogo.embed(FakeOAuthHttpFetcher(mapOf(LOGO to FetchedBody("image/png", huge))), LOGO))
        }

    private companion object {
        const val LOGO = "https://persistent.oaistatic.com/sonic/misc/openai-logo.png"
        val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13)
    }
}
