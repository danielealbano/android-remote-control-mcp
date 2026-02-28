package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import com.danielealbano.androidremotecontrolmcp.services.intents.SendIntentRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Intent Tools")
class IntentToolsTest {
    private lateinit var mockIntentDispatcher: IntentDispatcher

    @BeforeEach
    fun setUp() {
        mockIntentDispatcher = mockk(relaxed = true)
        mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.d(any(), any()) } returns 0
        io.mockk.every { android.util.Log.i(any(), any()) } returns 0
        io.mockk.every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.e(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ─── SendIntentHandler ───────────────────────────────────────────────

    @Nested
    @DisplayName("SendIntentHandler")
    inner class SendIntentTests {
        private lateinit var handler: SendIntentHandler

        @BeforeEach
        fun setUp() {
            handler = SendIntentHandler(mockIntentDispatcher)
        }

        @Test
        fun `send_intent activity with action returns success`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        put("action", "android.intent.action.VIEW")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Intent sent successfully"))
            }

        @Test
        fun `send_intent broadcast with action returns success`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "broadcast",
                            action = "android.intent.action.TEST",
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "broadcast")
                        put("action", "android.intent.action.TEST")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Intent sent successfully"))
            }

        @Test
        fun `send_intent service with action returns success`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "service",
                            action = "android.intent.action.TEST",
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "service")
                        put("action", "android.intent.action.TEST")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Intent sent successfully"))
            }

        @Test
        fun `send_intent missing type throws InvalidParams`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("action", "android.intent.action.VIEW")
                    }

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        fun `send_intent invalid type throws InvalidParams`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("type", "invalid")
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("type must be"))
            }

        @Test
        fun `send_intent with extras passes map to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            extras = mapOf("key1" to "value1", "count" to 42L),
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        putJsonObject("extras") {
                            put("key1", "value1")
                            put("count", 42)
                        }
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            extras = mapOf("key1" to "value1", "count" to 42L),
                        ),
                    )
                }
            }

        @Test
        fun `send_intent with extras_types passes overrides to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            extras = mapOf("id" to 42L),
                            extrasTypes = mapOf("id" to "long"),
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        putJsonObject("extras") {
                            put("id", 42)
                        }
                        putJsonObject("extras_types") {
                            put("id", "long")
                        }
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            extras = mapOf("id" to 42L),
                            extrasTypes = mapOf("id" to "long"),
                        ),
                    )
                }
            }

        @Test
        fun `send_intent with flags passes list to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            flags = listOf("FLAG_ACTIVITY_CLEAR_TOP"),
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        put(
                            "flags",
                            buildJsonArray {
                                add(JsonPrimitive("FLAG_ACTIVITY_CLEAR_TOP"))
                            },
                        )
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            flags = listOf("FLAG_ACTIVITY_CLEAR_TOP"),
                        ),
                    )
                }
            }

        @Test
        fun `send_intent with component passes string to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            component = "com.example/com.example.Activity",
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        put("component", "com.example/com.example.Activity")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            component = "com.example/com.example.Activity",
                        ),
                    )
                }
            }

        @Test
        fun `send_intent dispatcher failure returns error result`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )
                } returns Result.failure(IllegalArgumentException("No activity found to handle intent"))

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        put("action", "android.intent.action.VIEW")
                    }

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(params)
                }
            }

        @Test
        fun `send_intent with nested JsonObject extra skips unsupported value`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("valid" to "text"),
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        put("action", "android.intent.action.VIEW")
                        putJsonObject("extras") {
                            put("valid", "text")
                            putJsonObject("nested") {
                                put("inner", "value")
                            }
                        }
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("valid" to "text"),
                        ),
                    )
                }
            }

        @Test
        fun `send_intent with all parameters passes combined request to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            data = "https://example.com",
                            component = "com.example/com.example.Activity",
                            extras = mapOf("id" to 42L, "name" to "test"),
                            extrasTypes = mapOf("id" to "long"),
                            flags = listOf("FLAG_ACTIVITY_CLEAR_TOP"),
                        ),
                    )
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("type", "activity")
                        put("action", "android.intent.action.VIEW")
                        put("data", "https://example.com")
                        put("component", "com.example/com.example.Activity")
                        putJsonObject("extras") {
                            put("id", 42)
                            put("name", "test")
                        }
                        putJsonObject("extras_types") {
                            put("id", "long")
                        }
                        put(
                            "flags",
                            buildJsonArray {
                                add(JsonPrimitive("FLAG_ACTIVITY_CLEAR_TOP"))
                            },
                        )
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Intent sent successfully"))
                coVerify {
                    mockIntentDispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            data = "https://example.com",
                            component = "com.example/com.example.Activity",
                            extras = mapOf("id" to 42L, "name" to "test"),
                            extrasTypes = mapOf("id" to "long"),
                            flags = listOf("FLAG_ACTIVITY_CLEAR_TOP"),
                        ),
                    )
                }
            }
    }

    // ─── OpenUriHandler ──────────────────────────────────────────────────

    @Nested
    @DisplayName("OpenUriHandler")
    inner class OpenUriTests {
        private lateinit var handler: OpenUriHandler

        @BeforeEach
        fun setUp() {
            handler = OpenUriHandler(mockIntentDispatcher)
        }

        @Test
        fun `open_uri valid uri returns success`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.openUri("https://example.com", null, null)
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("uri", "https://example.com")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("URI opened successfully"))
            }

        @Test
        fun `open_uri missing uri throws InvalidParams`() =
            runTest {
                val params = buildJsonObject { }

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        fun `open_uri with package_name passes to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.openUri("https://example.com", "com.android.chrome", null)
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("uri", "https://example.com")
                        put("package_name", "com.android.chrome")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.openUri("https://example.com", "com.android.chrome", null)
                }
            }

        @Test
        fun `open_uri with mime_type passes to dispatcher`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.openUri("content://media/1", null, "image/jpeg")
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("uri", "content://media/1")
                        put("mime_type", "image/jpeg")
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                coVerify {
                    mockIntentDispatcher.openUri("content://media/1", null, "image/jpeg")
                }
            }

        @Test
        fun `open_uri dispatcher failure returns error result`() =
            runTest {
                coEvery {
                    mockIntentDispatcher.openUri("custom://unknown", null, null)
                } returns Result.failure(IllegalArgumentException("No app found to handle URI"))

                val params =
                    buildJsonObject {
                        put("uri", "custom://unknown")
                    }

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(params)
                }
            }
    }
}
