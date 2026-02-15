package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.auth.BearerTokenAuthPlugin
import com.danielealbano.androidremotecontrolmcp.mcp.mcpStreamableHttp
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerAppManagementTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerElementActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerFileTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerGestureTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerScreenIntrospectionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSystemActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTextInputTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTouchActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerUtilityTools
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Integration test helper that configures a Ktor [testApplication] with the same
 * plugin configuration as [com.danielealbano.androidremotecontrolmcp.mcp.McpServer].
 *
 * Uses the MCP Kotlin SDK [Server] and [Client] with [StreamableHttpClientTransport]
 * for full-stack integration testing through the Streamable HTTP endpoint at `/mcp`.
 *
 * @see com.danielealbano.androidremotecontrolmcp.mcp.McpServer
 */
object McpIntegrationTestHelper {
    const val TEST_BEARER_TOKEN = "test-integration-token"

    /**
     * Creates mocked service dependencies used by all tool handlers.
     */
    fun createMockDependencies(): MockDependencies =
        MockDependencies(
            actionExecutor = mockk(relaxed = true),
            accessibilityServiceProvider = mockk(relaxed = true),
            screenCaptureProvider = mockk(relaxed = true),
            treeParser = mockk(relaxed = true),
            elementFinder = mockk(relaxed = true),
            storageLocationProvider = mockk(relaxed = true),
            fileOperationProvider = mockk(relaxed = true),
            appManager = mockk(relaxed = true),
        )

    /**
     * Registers all MCP tools with the given [Server] using mocked dependencies.
     */
    fun registerAllTools(
        server: Server,
        deps: MockDependencies,
        deviceSlug: String = "",
    ) {
        val toolNamePrefix = McpToolUtils.buildToolNamePrefix(deviceSlug)
        registerScreenIntrospectionTools(
            server,
            deps.treeParser,
            deps.accessibilityServiceProvider,
            deps.screenCaptureProvider,
            CompactTreeFormatter(),
            toolNamePrefix,
        )
        registerSystemActionTools(
            server,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
            toolNamePrefix,
        )
        registerTouchActionTools(server, deps.actionExecutor, toolNamePrefix)
        registerGestureTools(server, deps.actionExecutor, toolNamePrefix)
        registerElementActionTools(
            server,
            deps.treeParser,
            deps.elementFinder,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
            toolNamePrefix,
        )
        registerTextInputTools(
            server,
            deps.treeParser,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
            toolNamePrefix,
        )
        registerUtilityTools(
            server,
            deps.treeParser,
            deps.elementFinder,
            deps.accessibilityServiceProvider,
            toolNamePrefix,
        )
        registerFileTools(server, deps.storageLocationProvider, deps.fileOperationProvider, toolNamePrefix)
        registerAppManagementTools(server, deps.appManager, toolNamePrefix)
    }

    /**
     * Mocks [android.util.Log] static methods to prevent crashes in JVM unit tests.
     * Must be called in @BeforeEach.
     */
    fun mockAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    /**
     * Unmocks [android.util.Log] static methods.
     * Must be called in @AfterEach.
     */
    fun unmockAndroidLog() {
        unmockkStatic(android.util.Log::class)
    }

    /**
     * Creates an SDK [Server] with all tools registered using the given dependencies.
     */
    fun createSdkServer(
        deps: MockDependencies,
        deviceSlug: String = "",
    ): Server {
        val server =
            Server(
                serverInfo =
                    Implementation(
                        name = McpToolUtils.buildServerName(deviceSlug),
                        version = "test",
                    ),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )
        registerAllTools(server, deps, deviceSlug)
        return server
    }

    /**
     * Runs a test within a fully configured Ktor [testApplication] using the MCP SDK
     * [Client] with [StreamableHttpClientTransport].
     *
     * The application is configured with ContentNegotiation (McpJson),
     * BearerTokenAuthPlugin, and mcpStreamableHttp, mirroring the production
     * McpServer setup.
     *
     * @param deps Mocked service dependencies (created via [createMockDependencies]).
     * @param testBlock The test code to execute with the SDK [Client] and [MockDependencies].
     */
    suspend fun withTestApplication(
        deps: MockDependencies = createMockDependencies(),
        deviceSlug: String = "",
        testBlock: suspend (client: Client, deps: MockDependencies) -> Unit,
    ) {
        val sdkServer = createSdkServer(deps, deviceSlug)

        testApplication {
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(BearerTokenAuthPlugin) { expectedToken = TEST_BEARER_TOKEN }
                mcpStreamableHttp { sdkServer }
            }

            val httpClient =
                createClient {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                        json(McpJson)
                    }
                    install(io.ktor.client.plugins.sse.SSE)
                }

            val transport =
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "/mcp",
                    requestBuilder = {
                        headers.append("Authorization", "Bearer $TEST_BEARER_TOKEN")
                    },
                )

            val mcpClient =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                )
            mcpClient.connect(transport)

            try {
                testBlock(mcpClient, deps)
            } finally {
                mcpClient.close()
            }
        }
    }

    /**
     * Runs a test within a fully configured Ktor [testApplication] using a raw
     * HTTP client (not the MCP SDK client). Useful for testing authentication
     * rejection where the SDK client would fail to connect.
     *
     * @param deps Mocked service dependencies (created via [createMockDependencies]).
     * @param testBlock The test code to execute within the testApplication.
     */
    suspend fun withRawTestApplication(
        deps: MockDependencies = createMockDependencies(),
        deviceSlug: String = "",
        testBlock: suspend io.ktor.server.testing.ApplicationTestBuilder.(MockDependencies) -> Unit,
    ) {
        val sdkServer = createSdkServer(deps, deviceSlug)

        testApplication {
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(BearerTokenAuthPlugin) { expectedToken = TEST_BEARER_TOKEN }
                mcpStreamableHttp { sdkServer }
            }

            testBlock(deps)
        }
    }
}

/**
 * Holds mocked service dependencies for integration tests.
 */
data class MockDependencies(
    val actionExecutor: ActionExecutor,
    val accessibilityServiceProvider: AccessibilityServiceProvider,
    val screenCaptureProvider: ScreenCaptureProvider,
    val treeParser: AccessibilityTreeParser,
    val elementFinder: ElementFinder,
    val storageLocationProvider: StorageLocationProvider,
    val fileOperationProvider: FileOperationProvider,
    val appManager: AppManager,
)
