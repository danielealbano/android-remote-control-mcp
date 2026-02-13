package com.danielealbano.androidremotecontrolmcp.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(
                    name = "get_clipboard",
                    arguments = emptyMap(),
                )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())

                val textContent = (result.content[0] as TextContent).text
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

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(
                    name = "set_clipboard",
                    arguments = mapOf("text" to "new clipboard content"),
                )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
            }
        }
}
