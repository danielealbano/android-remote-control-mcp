package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.services.intents.SendIntentRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Intent Tools Integration Tests")
class IntentToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `send_intent activity with action calls dispatcher and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "activity",
                        action = "android.intent.action.VIEW",
                    ),
                )
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "activity",
                                "action" to "android.intent.action.VIEW",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Intent sent successfully"))
            }
        }

    @Test
    fun `send_intent broadcast with extras passes extras through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "broadcast",
                        action = "com.example.ACTION",
                        extras = mapOf("key1" to "value1"),
                    ),
                )
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "broadcast",
                                "action" to "com.example.ACTION",
                                "extras" to mapOf("key1" to "value1"),
                            ),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.intentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "broadcast",
                            action = "com.example.ACTION",
                            extras = mapOf("key1" to "value1"),
                        ),
                    )
                }
            }
        }

    @Test
    fun `send_intent with component passes component through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "activity",
                        component = "com.example/com.example.Activity",
                    ),
                )
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "activity",
                                "component" to "com.example/com.example.Activity",
                            ),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    @Test
    fun `send_intent with flags passes flags through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "activity",
                        flags = listOf("FLAG_ACTIVITY_CLEAR_TOP"),
                    ),
                )
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "activity",
                                "flags" to listOf("FLAG_ACTIVITY_CLEAR_TOP"),
                            ),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    @Test
    fun `send_intent with extras_types passes overrides through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "activity",
                        extras = mapOf("id" to 42L),
                        extrasTypes = mapOf("id" to "long"),
                    ),
                )
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "activity",
                                "extras" to mapOf("id" to 42),
                                "extras_types" to mapOf("id" to "long"),
                            ),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    @Test
    fun `send_intent missing type returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments = mapOf("action" to "android.intent.action.VIEW"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }

    @Test
    fun `send_intent invalid type returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments = mapOf("type" to "invalid"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("type must be"))
            }
        }

    @Test
    fun `send_intent dispatcher failure returns error with message`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "activity",
                        action = "android.intent.action.VIEW",
                    ),
                )
            } returns Result.failure(IllegalArgumentException("No activity found to handle intent"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "activity",
                                "action" to "android.intent.action.VIEW",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("No activity found"))
            }
        }

    @Test
    fun `open_uri valid uri returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("https://example.com", null, null)
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments = mapOf("uri" to "https://example.com"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("URI opened successfully"))
            }
        }

    @Test
    fun `open_uri with package_name passes through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("https://example.com", "com.android.chrome", null)
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments =
                            mapOf(
                                "uri" to "https://example.com",
                                "package_name" to "com.android.chrome",
                            ),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.intentDispatcher.openUri("https://example.com", "com.android.chrome", null)
                }
            }
        }

    @Test
    fun `open_uri with mime_type passes through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("content://media/1", null, "image/jpeg")
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments =
                            mapOf(
                                "uri" to "content://media/1",
                                "mime_type" to "image/jpeg",
                            ),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.intentDispatcher.openUri("content://media/1", null, "image/jpeg")
                }
            }
        }

    @Test
    fun `open_uri missing uri returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }

    @Test
    fun `open_uri dispatcher failure returns error with message`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.openUri("custom://unknown", null, null)
            } returns Result.failure(IllegalArgumentException("No app found to handle URI"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_uri",
                        arguments = mapOf("uri" to "custom://unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("No app found"))
            }
        }

    @Test
    fun `send_intent SecurityException returns sanitized error message`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "activity",
                        action = "android.intent.action.VIEW",
                    ),
                )
            } returns
                Result.failure(
                    IllegalArgumentException("Permission denied: not allowed to start component"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "activity",
                                "action" to "android.intent.action.VIEW",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Permission denied"))
            }
        }

    @Test
    fun `send_intent IllegalStateException returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.intentDispatcher.sendIntent(
                    SendIntentRequest(
                        type = "service",
                        action = "android.intent.action.TEST",
                    ),
                )
            } returns
                Result.failure(
                    IllegalStateException("Cannot start component: background start restriction"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_send_intent",
                        arguments =
                            mapOf(
                                "type" to "service",
                                "action" to "android.intent.action.TEST",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("background start restriction"))
            }
        }
}
