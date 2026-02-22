package com.danielealbano.androidremotecontrolmcp.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
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
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_a",
                        className = "android.widget.Button",
                        text = "Hello World",
                        contentDescription = "A button",
                        bounds = BoundsData(50, 800, 250, 1000),
                        clickable = true,
                        enabled = true,
                        visible = true,
                    ),
                    AccessibilityNodeData(
                        id = "node_b",
                        className = "android.widget.TextView",
                        bounds = BoundsData(50, 1000, 250, 1200),
                        visible = true,
                        enabled = true,
                    ),
                ),
        )

    private val sampleScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

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
                val result =
                    client.callTool(
                        name = "android_get_clipboard",
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
                val result =
                    client.callTool(
                        name = "android_set_clipboard",
                        arguments = mapOf("text" to "new clipboard content"),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
            }
        }

    @Test
    fun `get_element_details returns TSV with element_id header and correct values`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)

            every {
                deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_a")
            } returns
                AccessibilityNodeData(
                    id = "node_a",
                    className = "android.widget.Button",
                    text = "Hello World",
                    contentDescription = "A button",
                    bounds = BoundsData(50, 800, 250, 1000),
                    clickable = true,
                    enabled = true,
                    visible = true,
                )

            every {
                deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_b")
            } returns
                AccessibilityNodeData(
                    id = "node_b",
                    className = "android.widget.TextView",
                    bounds = BoundsData(50, 1000, 250, 1200),
                    visible = true,
                    enabled = true,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_element_details",
                        arguments = mapOf("element_ids" to listOf("node_a", "node_b")),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())

                val textContent = (result.content[0] as TextContent).text
                val lines = textContent.split("\n")
                assertEquals("element_id\ttext\tdesc", lines[0])
                assertEquals("node_a\tHello World\tA button", lines[1])
                assertEquals("node_b\t-\t-", lines[2])
            }
        }
}
