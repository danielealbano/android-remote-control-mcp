package com.danielealbano.androidremotecontrolmcp.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Utility Integration Tests")
class UtilityIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `get_clipboard returns clipboard content from mocked service`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockContext = mockk<Context>()
            val mockClipboardManager = mockk<ClipboardManager>()
            val mockClipData = mockk<ClipData>()
            val mockItem = mockk<ClipData.Item>()

            every { deps.accessibilityServiceProvider.getContext() } returns mockContext
            every {
                mockContext.getSystemService(ClipboardManager::class.java)
            } returns mockClipboardManager
            every { mockClipboardManager.primaryClip } returns mockClipData
            every { mockClipData.itemCount } returns 1
            every { mockClipData.getItemAt(0) } returns mockItem
            every { mockItem.text } returns "clipboard text"

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "get_clipboard")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val textContent =
                    rpcResponse.result!!.jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!.jsonPrimitive.content
                val parsed = Json.parseToJsonElement(textContent).jsonObject
                assertEquals(
                    "clipboard text",
                    parsed["text"]?.jsonPrimitive?.content,
                )
            }
        }

    @Test
    fun `set_clipboard sets content and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockContext = mockk<Context>()
            val mockClipboardManager = mockk<ClipboardManager>(relaxed = true)

            every { deps.accessibilityServiceProvider.getContext() } returns mockContext
            every {
                mockContext.getSystemService(ClipboardManager::class.java)
            } returns mockClipboardManager

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "set_clipboard",
                        arguments =
                            buildJsonObject {
                                put("text", "new clipboard content")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }
}
