package com.danielealbano.androidremotecontrolmcp.integration

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthPlugin
import com.danielealbano.androidremotecontrolmcp.mcp.contentTypeOrOctetStream
import com.danielealbano.androidremotecontrolmcp.mcp.installMcpStatelessTransport
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LoggedToolRegistrar
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSharingTools
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkServiceImpl
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInboxImpl
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedItem
import com.danielealbano.androidremotecontrolmcp.services.storage.FileBytesResult
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Sharing Integration Tests")
class SharingIntegrationTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class, BitmapFactory::class, Base64::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Base64.encodeToString(any<ByteArray>(), any()) } returns INLINE_IMAGE_BASE64
        // Bounds decode reports a small image so the classifier keeps the original bytes (no real bitmap decode).
        every {
            BitmapFactory.decodeByteArray(any<ByteArray>(), any<Int>(), any<Int>(), any<BitmapFactory.Options>())
        } answers {
            lastArg<BitmapFactory.Options>().apply {
                outWidth = 10
                outHeight = 10
            }
            null
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class, BitmapFactory::class, Base64::class)
    }

    @Test
    @DisplayName("text item: warning first, text inline, inbox emptied")
    fun textItem() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            inbox.add(textItem("hello from share"))

            runSharingApp(inbox, linkService) { client, _ ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertEquals(2, result.content.size)
                assertWarningFirst(result.content)
                assertEquals("hello from share", (result.content[1] as TextContent).text)

                // Consume-on-read: a second call drains nothing and returns the empty-inbox guidance.
                val second = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertTrue((second.content[0] as TextContent).text.contains("no shared content available"))
            }
        }

    @Test
    @DisplayName("image: warning first, then ImageContent, then URL text with user-only flag")
    fun imageItem() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            inbox.add(blobItem("pic.png", "image/png", byteArrayOf(1, 2, 3, 4)))

            runSharingApp(inbox, linkService) { client, _ ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertWarningFirst(result.content)
                val image = result.content[1] as ImageContent
                assertEquals(INLINE_IMAGE_BASE64, image.data)
                assertEquals("image/png", image.mimeType)
                val urlText = (result.content[2] as TextContent).text
                assertTrue(urlText.contains("/s/"), "must contain a capability URL")
                assertTrue(urlText.contains("Only share this URL"), "must flag the URL as user-only")
            }
        }

    @Test
    @DisplayName("pdf: URL text; token resolves via /s/{token}")
    fun pdfItem() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
            inbox.add(blobItem("doc.pdf", "application/pdf", bytes))

            runSharingApp(inbox, linkService) { client, httpClient ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertWarningFirst(result.content)
                val urlText = (result.content[1] as TextContent).text
                assertTrue(urlText.contains("web_fetch"), "file note must mention web_fetch")
                val token = TOKEN_REGEX.find(urlText)?.groupValues?.get(1)
                assertTrue(token != null, "URL must contain a token")

                val response = httpClient.get("/s/$token")
                assertEquals(HttpStatusCode.OK, response.status)
                assertArrayEquals(bytes, response.readRawBytes())
            }
        }

    @Test
    @DisplayName("a textual file is returned inline as text, not as a URL")
    fun textualFileInlined() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val json = """{"hello":"world"}"""
            inbox.add(blobItem("data.json", "application/json", json.toByteArray()))

            runSharingApp(inbox, linkService) { client, _ ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertWarningFirst(result.content)
                val text = (result.content[1] as TextContent).text
                assertTrue(text.contains(json), "textual file content must be inlined")
                assertFalse(text.contains("/s/"), "a textual file must NOT be served as a capability URL")
            }
        }

    @Test
    @DisplayName("a blob with a malformed MIME is served as octet-stream with no Content-Disposition")
    fun malformedMimeServedAsOctetStream() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val bytes = byteArrayOf(9, 8, 7)
            inbox.add(blobItem("weird.bin", "this is not a mime", bytes))

            runSharingApp(inbox, linkService) { client, httpClient ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                val token = TOKEN_REGEX.find((result.content[1] as TextContent).text)!!.groupValues[1]

                val response = httpClient.get("/s/$token")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(ContentType.Application.OctetStream, response.contentType())
                assertNull(response.headers["Content-Disposition"], "/s/ must not set Content-Disposition")
                assertArrayEquals(bytes, response.readRawBytes())
            }
        }

    @Test
    @DisplayName("empty inbox returns guidance")
    fun emptyInbox() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()

            runSharingApp(inbox, linkService) { client, _ ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertEquals(1, result.content.size)
                assertTrue((result.content[0] as TextContent).text.contains("no shared content available"))
            }
        }

    @Test
    @DisplayName("a non-text item carrying no bytes is surfaced as skipped, not dropped silently")
    fun blobWithoutBytesSurfaced() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            inbox.add(
                SharedItem(
                    id = "ghost",
                    kind = SharedItem.Kind.BLOB,
                    mimeType = "application/pdf",
                    fileName = "ghost.pdf",
                    text = null,
                    bytes = null,
                    sizeBytes = 0L,
                    createdAtMs = 0L,
                    expiresAtMs = Long.MAX_VALUE,
                ),
            )

            runSharingApp(inbox, linkService) { client, _ ->
                val result = client.callTool(name = "get_shared_content", arguments = emptyMap())
                assertTrue(
                    result.content.any { it is TextContent && it.text.contains("had no readable content") },
                    "an item with no bytes must be surfaced, not silently dropped",
                )
            }
        }

    @Test
    @DisplayName("share_file_via_web returns a capability url that resolves via /s/{token}")
    fun shareFileViaWebResolves() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val fop = mockk<FileOperationProvider>(relaxed = true)
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            coEvery { fop.readFileBytes("loc1", "doc.pdf", any()) } returns
                FileBytesResult(bytes, "application/pdf", "doc.pdf", bytes.size.toLong())

            runSharingApp(inbox, linkService, SharingTestConfig(fileOperationProvider = fop)) { client, httpClient ->
                val result =
                    client.callTool(
                        name = "share_file_via_web",
                        arguments = mapOf("location_id" to "loc1", "path" to "doc.pdf"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING), "warning must be first")
                val token = TOKEN_REGEX.find(text)?.groupValues?.get(1)
                assertTrue(token != null, "result must contain a capability URL")

                val response = httpClient.get("/s/$token")
                assertEquals(HttpStatusCode.OK, response.status)
                assertArrayEquals(bytes, response.readRawBytes())
            }
        }

    @Test
    @DisplayName("share_file_via_web rejects an over-limit file and registers no link")
    fun shareFileViaWebRejectsOverLimit() =
        runTest {
            val inbox = newInbox()
            val linkService = mockk<EphemeralFileLinkService>(relaxed = true)
            val fop = mockk<FileOperationProvider>(relaxed = true)
            coEvery { fop.readFileBytes(any(), any(), any()) } throws
                McpToolException.ActionFailed("File size (999 bytes) exceeds the limit of 1 bytes.")

            runSharingApp(inbox, linkService, SharingTestConfig(fileOperationProvider = fop)) { client, _ ->
                val result =
                    client.callTool(
                        name = "share_file_via_web",
                        arguments = mapOf("location_id" to "loc1", "path" to "big.bin"),
                    )
                assertEquals(true, result.isError)
                coVerify(exactly = 0) { linkService.register(any(), any(), any()) }
            }
        }

    @Test
    @DisplayName("share_file_via_web reuses the link registry (21st call evicts the oldest)")
    fun shareFileViaWebReusesRegistryCap() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val fop = mockk<FileOperationProvider>(relaxed = true)
            coEvery { fop.readFileBytes(any(), any(), any()) } answers {
                val path = secondArg<String>()
                val data = path.toByteArray()
                FileBytesResult(data, "application/octet-stream", path, data.size.toLong())
            }

            runSharingApp(inbox, linkService, SharingTestConfig(fileOperationProvider = fop)) { client, httpClient ->
                val tokens = mutableListOf<String>()
                repeat(EphemeralFileLinkService.MAX_LINKS + 1) { i ->
                    val result =
                        client.callTool(
                            name = "share_file_via_web",
                            arguments = mapOf("location_id" to "loc1", "path" to "f$i.bin"),
                        )
                    val text = (result.content[0] as TextContent).text
                    tokens += TOKEN_REGEX.find(text)!!.groupValues[1]
                }

                assertEquals(
                    HttpStatusCode.NotFound,
                    httpClient.get("/s/${tokens.first()}").status,
                    "the oldest link must be evicted",
                )
                assertEquals(
                    HttpStatusCode.OK,
                    httpClient.get("/s/${tokens.last()}").status,
                    "the newest link must resolve",
                )
            }
        }

    @Test
    @DisplayName("share_file_via_web URL reflects X-Forwarded-Host and -Proto")
    fun shareFileViaWebReflectsForwardedHeaders() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val fop = mockk<FileOperationProvider>(relaxed = true)
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            coEvery { fop.readFileBytes("loc1", "doc.pdf", any()) } returns
                FileBytesResult(bytes, "application/pdf", "doc.pdf", bytes.size.toLong())

            runSharingApp(
                inbox,
                linkService,
                SharingTestConfig(
                    fileOperationProvider = fop,
                    requestHeaders =
                        mapOf(
                            "X-Forwarded-Host" to "tunnel.example.com",
                            "X-Forwarded-Proto" to "https",
                        ),
                ),
            ) { client, _ ->
                val result =
                    client.callTool(
                        name = "share_file_via_web",
                        arguments = mapOf("location_id" to "loc1", "path" to "doc.pdf"),
                    )
                val text = (result.content[0] as TextContent).text
                assertTrue(
                    text.contains("https://tunnel.example.com/s/"),
                    "share URL must reflect X-Forwarded-Host and -Proto",
                )
            }
        }

    @Test
    @DisplayName("share URL falls back to request host when no forwarded headers")
    fun shareUrlFallsBackWithoutForwardedHeaders() =
        runTest {
            val inbox = newInbox()
            val linkService = newLinkService()
            val fop = mockk<FileOperationProvider>(relaxed = true)
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            coEvery { fop.readFileBytes("loc1", "doc.pdf", any()) } returns
                FileBytesResult(bytes, "application/pdf", "doc.pdf", bytes.size.toLong())

            runSharingApp(inbox, linkService, SharingTestConfig(fileOperationProvider = fop)) { client, httpClient ->
                val result =
                    client.callTool(
                        name = "share_file_via_web",
                        arguments = mapOf("location_id" to "loc1", "path" to "doc.pdf"),
                    )
                val text = (result.content[0] as TextContent).text
                assertFalse(text.contains("tunnel.example.com"), "no forwarded host must not leak into the URL")
                val token = TOKEN_REGEX.find(text)!!.groupValues[1]
                assertEquals(
                    HttpStatusCode.OK,
                    httpClient.get("/s/$token").status,
                    "default behavior unchanged: link still resolves",
                )
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun newInbox(): SharedContentInboxImpl = SharedContentInboxImpl()

    private fun newLinkService(): EphemeralFileLinkServiceImpl = EphemeralFileLinkServiceImpl()

    private fun textItem(text: String): SharedItem =
        SharedItem(
            id = text.hashCode().toString(),
            kind = SharedItem.Kind.TEXT,
            mimeType = "text/plain",
            fileName = null,
            text = text,
            bytes = null,
            sizeBytes = text.toByteArray().size.toLong(),
            createdAtMs = 0L,
            expiresAtMs = Long.MAX_VALUE,
        )

    private fun blobItem(
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ): SharedItem =
        SharedItem(
            id = name,
            kind = SharedItem.Kind.BLOB,
            mimeType = mimeType,
            fileName = name,
            text = null,
            bytes = bytes,
            sizeBytes = bytes.size.toLong(),
            createdAtMs = 0L,
            expiresAtMs = Long.MAX_VALUE,
        )

    private fun newServer(): Server =
        Server(
            serverInfo = Implementation(name = "sharing-test", version = "test"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        )

    private fun assertWarningFirst(content: List<Any>) {
        assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, (content[0] as TextContent).text)
    }

    private data class SharingTestConfig(
        val fileOperationProvider: FileOperationProvider = mockk(relaxed = true),
        val requestHeaders: Map<String, String> = emptyMap(),
    )

    private suspend fun runSharingApp(
        inbox: SharedContentInbox,
        linkService: EphemeralFileLinkService,
        config: SharingTestConfig = SharingTestConfig(),
        block: suspend (Client, io.ktor.client.HttpClient) -> Unit,
    ) {
        val server = newServer()
        val inFlightToolCalls = InFlightToolCalls()
        registerSharingTools(
            LoggedToolRegistrar(server, RecordingServerLogRepository(), inFlightToolCalls = inFlightToolCalls),
            inbox,
            linkService,
            config.fileOperationProvider,
            FILE_SIZE_LIMIT_MB,
            { BASE_URL },
            PrivacyToolTestDoubles.passthroughGate(),
            "",
            ToolPermissionsConfig(),
        )

        testApplication {
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(McpAuthPlugin) {
                    // Open /mcp for the sharing tests: bearer disabled (not empty-token), oauth off.
                    bearerTokenEnabled = false
                    excludedPaths = setOf("/health")
                    excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX)
                }
                installMcpStatelessTransport(inFlightToolCalls = inFlightToolCalls) { server }
                routing {
                    get("${EphemeralFileLinkService.PATH_PREFIX}{token}") {
                        val entry = linkService.resolve(call.parameters["token"].orEmpty())
                        if (entry == null) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.respondBytes(
                                entry.bytes,
                                contentTypeOrOctetStream(entry.mimeType),
                                HttpStatusCode.OK,
                            )
                        }
                    }
                }
            }

            val httpClient =
                createClient {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(McpJson) }
                    install(io.ktor.client.plugins.sse.SSE)
                }
            val transport =
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "/mcp",
                    requestBuilder = { config.requestHeaders.forEach { (k, v) -> header(k, v) } },
                )
            val mcpClient = Client(clientInfo = Implementation(name = "test-client", version = "1.0.0"))
            mcpClient.connect(transport)
            try {
                block(mcpClient, httpClient)
            } finally {
                mcpClient.close()
            }
        }
    }

    private companion object {
        const val BASE_URL = "http://test.local"
        const val FILE_SIZE_LIMIT_MB = 50
        const val INLINE_IMAGE_BASE64 = "INLINEIMG"
        val TOKEN_REGEX = Regex("/s/([0-9a-f]+)")
    }
}
