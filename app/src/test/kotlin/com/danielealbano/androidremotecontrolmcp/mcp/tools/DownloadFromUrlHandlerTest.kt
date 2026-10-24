package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

@DisplayName("DownloadFromUrlHandler")
class DownloadFromUrlHandlerTest {
    private val provider = mockk<FileOperationProvider>()
    private val handler = DownloadFromUrlHandler(provider)
    private val arguments =
        buildJsonObject {
            put("location_id", "loc1")
            put("path", "file.bin")
            put("url", "https://example.com/file.bin")
        }

    @Test
    fun `a cancelled download stays a cancellation, not a failed download`() =
        runTest {
            coEvery { provider.downloadFromUrl(any(), any(), any()) } throws CancellationException("stop")

            val e = assertThrows<CancellationException> { handler.execute(arguments) }

            assertEquals("stop", e.message)
        }

    @Test
    fun `any other failure becomes a failed download`() =
        runTest {
            coEvery { provider.downloadFromUrl(any(), any(), any()) } throws IOException("reset")

            val e = assertThrows<McpToolException.ActionFailed> { handler.execute(arguments) }

            assertEquals("Failed to download file: reset", e.message)
        }
}
