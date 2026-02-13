package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.JsonRpcRequest
import com.danielealbano.androidremotecontrolmcp.mcp.JsonRpcResponse
import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import com.danielealbano.androidremotecontrolmcp.mcp.auth.BearerTokenAuthPlugin
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolRegistry
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerElementActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerGestureTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerScreenIntrospectionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSystemActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTextInputTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTouchActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerUtilityTools
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Integration test helper that configures a Ktor [testApplication] with the same
 * routing and plugin configuration as [com.danielealbano.androidremotecontrolmcp.mcp.McpServer].
 *
 * Provides mocked Android service dependencies and utility methods for sending
 * JSON-RPC requests through the full HTTP stack.
 *
 * Routing structure mirrors McpServer.configureRouting():
 * - GET /health — unauthenticated
 * - POST /mcp/v1/initialize — authenticated
 * - GET /mcp/v1/tools/list — authenticated
 * - POST /mcp/v1/tools/call — authenticated
 *
 * @see com.danielealbano.androidremotecontrolmcp.mcp.McpServer
 */
object McpIntegrationTestHelper {
    const val TEST_BEARER_TOKEN = "test-integration-token"
    const val HEALTH_PATH = "/health"
    const val INITIALIZE_PATH = "/mcp/v1/initialize"
    const val TOOLS_LIST_PATH = "/mcp/v1/tools/list"
    const val TOOLS_CALL_PATH = "/mcp/v1/tools/call"

    /**
     * Creates mocked service dependencies used by all tool handlers.
     */
    fun createMockDependencies(): MockDependencies {
        return MockDependencies(
            actionExecutor = mockk(relaxed = true),
            accessibilityServiceProvider = mockk(relaxed = true),
            screenCaptureProvider = mockk(relaxed = true),
            treeParser = mockk(relaxed = true),
            elementFinder = mockk(relaxed = true),
        )
    }

    /**
     * Registers all 29 MCP tools with the given [ToolRegistry] using mocked dependencies.
     */
    fun registerAllTools(
        toolRegistry: ToolRegistry,
        deps: MockDependencies,
    ) {
        registerScreenIntrospectionTools(
            toolRegistry,
            deps.treeParser,
            deps.accessibilityServiceProvider,
            deps.screenCaptureProvider,
        )
        registerSystemActionTools(
            toolRegistry,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
        )
        registerTouchActionTools(toolRegistry, deps.actionExecutor)
        registerGestureTools(toolRegistry, deps.actionExecutor)
        registerElementActionTools(
            toolRegistry,
            deps.treeParser,
            deps.elementFinder,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
        )
        registerTextInputTools(
            toolRegistry,
            deps.treeParser,
            deps.actionExecutor,
            deps.accessibilityServiceProvider,
        )
        registerUtilityTools(
            toolRegistry,
            deps.treeParser,
            deps.elementFinder,
            deps.accessibilityServiceProvider,
        )
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
     * Runs a test within a fully configured Ktor [testApplication].
     *
     * The application is configured with ContentNegotiation, StatusPages,
     * BearerTokenAuthPlugin, and all MCP routes, mirroring the production
     * McpServer setup.
     *
     * @param deps Mocked service dependencies (created via [createMockDependencies]).
     * @param testBlock The test code to execute within the [ApplicationTestBuilder].
     */
    suspend fun withTestApplication(
        deps: MockDependencies = createMockDependencies(),
        testBlock: suspend ApplicationTestBuilder.(MockDependencies) -> Unit,
    ) {
        val toolRegistry = ToolRegistry()
        registerAllTools(toolRegistry, deps)
        val protocolHandler = McpProtocolHandler(toolRegistry)

        testApplication {
            application {
                configurePlugins()
                configureRouting(protocolHandler)
            }
            testBlock(deps)
        }
    }

    private fun io.ktor.server.application.Application.configurePlugins() {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = false
                    isLenient = false
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    buildJsonObject {
                        put("error", "internal_server_error")
                        put("message", cause.message ?: "Unknown error")
                    },
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun io.ktor.server.application.Application.configureRouting(protocolHandler: McpProtocolHandler) {
        routing {
            configureHealthRoute()
            configureMcpRoutes(protocolHandler)
        }
    }

    private fun io.ktor.server.routing.Route.configureHealthRoute() {
        get("/health") {
            call.respondText(
                contentType = ContentType.Application.Json,
                text =
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("status", "healthy")
                            put("version", "test")
                            put("server", "running")
                        },
                    ),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun io.ktor.server.routing.Route.configureMcpRoutes(protocolHandler: McpProtocolHandler) {
        route("/mcp") {
            install(BearerTokenAuthPlugin) {
                expectedToken = TEST_BEARER_TOKEN
            }

            route("/v1") {
                post("/initialize") {
                    val request =
                        try {
                            call.receive<JsonRpcRequest>()
                        } catch (e: Exception) {
                            call.respond(protocolHandler.parseError(null))
                            return@post
                        }
                    val response = protocolHandler.handleRequest(request)
                    call.respond(response)
                }

                get("/tools/list") {
                    val idParam = call.request.queryParameters["id"]
                    val requestId: kotlinx.serialization.json.JsonElement? =
                        idParam?.let { id ->
                            id.toLongOrNull()?.let { JsonPrimitive(it) }
                                ?: JsonPrimitive(id)
                        }
                    val request =
                        JsonRpcRequest(
                            method = McpProtocolHandler.METHOD_TOOLS_LIST,
                            id = requestId,
                        )
                    val response = protocolHandler.handleRequest(request)
                    call.respond(response)
                }

                post("/tools/call") {
                    val request =
                        try {
                            call.receive<JsonRpcRequest>()
                        } catch (e: Exception) {
                            call.respond(protocolHandler.parseError(null))
                            return@post
                        }
                    val response = protocolHandler.handleRequest(request)
                    call.respond(response)
                }
            }
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
)

/**
 * Sends a JSON-RPC request to the tools/call endpoint with bearer token authentication.
 *
 * @param toolName The MCP tool name to call.
 * @param arguments Optional JSON arguments for the tool.
 * @param token The bearer token to use (defaults to [McpIntegrationTestHelper.TEST_BEARER_TOKEN]).
 * @return The HTTP response.
 */
suspend fun ApplicationTestBuilder.sendToolCall(
    toolName: String,
    arguments: JsonObject? = null,
    token: String = McpIntegrationTestHelper.TEST_BEARER_TOKEN,
): HttpResponse {
    val body =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", toolName)
                    if (arguments != null) {
                        put("arguments", arguments)
                    }
                },
            )
        }

    return client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(JsonObject.serializer(), body))
    }
}

/**
 * Parses an HTTP response body as a [JsonRpcResponse].
 */
suspend fun HttpResponse.toJsonRpcResponse(): JsonRpcResponse {
    return Json.decodeFromString(JsonRpcResponse.serializer(), bodyAsText())
}
