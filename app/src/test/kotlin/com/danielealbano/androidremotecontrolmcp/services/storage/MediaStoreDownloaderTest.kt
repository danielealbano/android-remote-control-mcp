package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL

@ExtendWith(MockKExtension::class)
@DisplayName("MediaStoreDownloader")
class MediaStoreDownloaderTest {
    @MockK(relaxed = true)
    private lateinit var mockContext: Context

    @MockK(relaxed = true)
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockHttpClient: DownloadHttpClient

    private val insertUri: Uri = mockk(relaxed = true)
    private val url = URL("http://example.com/file.bin")

    private lateinit var downloader: MediaStoreDownloader

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContentResolver.delete(any(), any(), any()) } returns 1
        every { mockContentResolver.update(any(), any(), any(), any()) } returns 1

        downloader = MediaStoreDownloader(mockContext, mockHttpClient)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /** Matches [url] by its text: `URL.equals` resolves host names, which a unit test must not do. */
    private fun MockKMatcherScope.sameUrl(): URL = match { it.toString() == url.toString() }

    private fun respond(response: FakeDownloadResponse): FakeDownloadResponse {
        every { mockHttpClient.open(sameUrl(), any()) } returns response
        return response
    }

    private fun download(config: ServerConfig = ServerConfig()): Long =
        downloader.downloadToPendingUri(
            insertUri,
            url,
            "http://example.com/file.bin",
            config,
            "builtin:downloads/file.bin",
        )

    @Test
    fun `downloadToPendingUri streams bytes and clears IS_PENDING`() {
        val response = respond(FakeDownloadResponse(200, 5L, ByteArrayInputStream("hello".toByteArray())))
        val output = ByteArrayOutputStream()
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns output

        val result = download()

        assertEquals(5L, result)
        assertEquals("hello", output.toString(Charsets.UTF_8.name()))
        verify { mockContentResolver.update(eq(insertUri), any(), any(), any()) }
        verify(exactly = 0) { mockContentResolver.delete(any(), any(), any()) }
        assertTrue(response.closed)
    }

    @Test
    fun `downloadToPendingUri passes the config to the http client`() {
        respond(FakeDownloadResponse(200, 0L))
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns ByteArrayOutputStream()
        val config = ServerConfig(blockLocalNetworkDownloads = true, downloadTimeoutSeconds = 42)

        download(config)

        verify { mockHttpClient.open(sameUrl(), config) }
    }

    @Test
    fun `downloadToPendingUri deletes entry on http error status`() {
        val response = respond(FakeDownloadResponse(404))

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("HTTP status 404"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
        verify(exactly = 0) { mockContentResolver.update(any(), any(), any(), any()) }
        assertTrue(response.closed)
    }

    @Test
    fun `downloadToPendingUri rejects oversized reported content length`() {
        val response = respond(FakeDownloadResponse(200, 2L * 1024 * 1024))

        val exception =
            assertThrows<McpToolException.ActionFailed> {
                download(ServerConfig(fileSizeLimitMb = 1))
            }

        assertTrue(exception.message!!.contains("exceeds limit"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
        assertTrue(response.closed)
    }

    @Test
    fun `downloadToPendingUri rejects stream exceeding size limit`() {
        respond(FakeDownloadResponse(200, -1L, ByteArrayInputStream(ByteArray(1024 * 1024 + 1))))
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns
            ByteArrayOutputStream()

        val exception =
            assertThrows<McpToolException.ActionFailed> {
                download(ServerConfig(fileSizeLimitMb = 1))
            }

        assertTrue(exception.message!!.contains("file size limit"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
        verify(exactly = 0) { mockContentResolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `downloadToPendingUri throws when destination cannot be opened`() {
        respond(FakeDownloadResponse(200, 5L))
        every { mockContentResolver.openOutputStream(eq(insertUri), eq("wt")) } returns null

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("Failed to open download destination"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
    }

    @Test
    fun `downloadToPendingUri wraps connect failure with cause and deletes entry`() {
        every { mockHttpClient.open(sameUrl(), any()) } throws IOException("boom")

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("Download failed: boom"))
        assertTrue(exception.cause is IOException)
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
    }

    @Test
    fun `downloadToPendingUri reports a blocked local network connection`() {
        every { mockHttpClient.open(sameUrl(), any()) } throws
            LocalNetworkDownloadBlockedException(InetSocketAddress("127.0.0.1", 80))

        val exception = assertThrows<McpToolException.ActionFailed> { download() }

        assertTrue(exception.message!!.contains("Block Local Network Downloads"))
        verify { mockContentResolver.delete(eq(insertUri), any(), any()) }
    }
}
