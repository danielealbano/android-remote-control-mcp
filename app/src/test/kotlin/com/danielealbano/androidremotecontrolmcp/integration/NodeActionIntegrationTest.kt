@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.stripUntrustedWarning
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Node Action Integration Tests")
class NodeActionIntegrationTest {
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_btn",
                        className = "android.widget.Button",
                        text = "OK",
                        bounds = BoundsData(50, 800, 250, 1000),
                        clickable = true,
                        enabled = true,
                        visible = true,
                    ),
                ),
        )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private val sampleScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    @Test
    fun `find_nodes returns matching nodes from mocked tree`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "OK", false)
            } returns
                listOf(
                    ElementInfo(
                        id = "node_btn",
                        text = "OK",
                        className = "android.widget.Button",
                        bounds = BoundsData(50, 800, 250, 1000),
                        clickable = true,
                        enabled = true,
                        visible = true,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_find_nodes",
                        arguments = mapOf("by" to "text", "value" to "OK"),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())

                val textContent = (result.content[0] as TextContent).text
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(textContent)).jsonObject
                val elements = parsed["nodes"]!!.jsonArray
                assertEquals(1, elements.size)
                assertEquals(
                    "node_btn",
                    elements[0].jsonObject["node_id"]?.jsonPrimitive?.content,
                )
            }
        }

    @Test
    fun `click_node with valid node_id calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_btn", any<List<WindowData>>())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_node",
                        arguments = mapOf("node_id" to "node_btn"),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
            }
        }

    private fun okButton(id: String) =
        ElementInfo(
            id = id,
            text = "OK",
            className = "android.widget.Button",
            bounds = BoundsData(50, 800, 250, 1000),
            clickable = true,
            enabled = true,
            visible = true,
        )

    @Test
    fun `click_node with a selector clicks the single on-screen match`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "OK", true)
            } returns listOf(okButton("node_btn"))
            coEvery {
                deps.actionExecutor.clickNode("node_btn", any<List<WindowData>>())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_node",
                        arguments = mapOf("by" to "text", "value" to "OK", "exact_match" to true),
                    )
                assertNotEquals(true, result.isError)
                assertTrue((result.content[0] as TextContent).text.contains("node_btn"))
            }
        }

    @Test
    fun `click_node with an ambiguous selector returns the untrusted candidates as the error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "OK", true)
            } returns listOf(okButton("node_ok_1"), okButton("node_ok_2"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_node",
                        arguments = mapOf("by" to "text", "value" to "OK", "exact_match" to true),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.lines().first())
                assertTrue(text.contains("node_ok_1") && text.contains("node_ok_2"), text)
            }
            coVerify(exactly = 0) { deps.actionExecutor.clickNode(any(), any()) }
        }

    @Test
    fun `node tools accept node_id or a selector, and scroll_node is listed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val tools = client.listTools().tools.associateBy { it.name }
                val nodeTools =
                    listOf(
                        "click_node",
                        "long_click_node",
                        "tap_node",
                        "scroll_to_node",
                        "scroll_node",
                        "type_append_text",
                        "type_insert_text",
                        "type_replace_text",
                        "type_clear_text",
                    )
                for (name in nodeTools) {
                    val tool = tools.getValue("android_$name")
                    val properties =
                        tool.inputSchema.properties
                            ?.keys
                            .orEmpty()
                    for (property in listOf("node_id", "by", "value", "exact_match")) {
                        assertTrue(property in properties, "$name misses $property")
                    }
                    assertFalse(
                        tool.inputSchema.required
                            .orEmpty()
                            .contains("node_id"),
                        "$name requires node_id",
                    )
                }
                assertTrue(
                    "visible_only" in
                        tools
                            .getValue("android_find_nodes")
                            .inputSchema.properties!!
                            .keys,
                )
                assertEquals(listOf("direction"), tools.getValue("android_scroll_node").inputSchema.required)
                assertEquals(listOf("direction"), tools.getValue("android_scroll_to_node").inputSchema.required)
            }
        }

    @Test
    fun `scroll_node scrolls the node once in the given direction`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.scrollNode("node_root", ScrollDirection.DOWN, any<List<WindowData>>())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_scroll_node",
                        arguments = mapOf("node_id" to "node_root", "direction" to "down"),
                    )
                assertNotEquals(true, result.isError)
                assertEquals("Scrolled node 'node_root' down", (result.content[0] as TextContent).text)
            }
            coVerify(exactly = 1) { deps.actionExecutor.scrollNode(any(), any(), any()) }
        }

    @Test
    fun `scroll_to_node with a selector resolves the match`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "OK", true)
            } returns listOf(okButton("node_btn"))
            every { deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_btn") } returns
                sampleTree.children[0]

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_scroll_to_node",
                        arguments =
                            mapOf("by" to "text", "value" to "OK", "exact_match" to true, "direction" to "down"),
                    )
                assertNotEquals(true, result.isError)
                assertEquals("Node 'node_btn' is already visible", (result.content[0] as TextContent).text)
            }
        }

    @Test
    fun `find_nodes with visible_only returns only the on-screen matches`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "OK", false)
            } returns listOf(okButton("node_on"), okButton("node_off").copy(visible = false))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_find_nodes",
                        arguments = mapOf("by" to "text", "value" to "OK", "visible_only" to true),
                    )
                assertNotEquals(true, result.isError)
                val text = stripUntrustedWarning((result.content[0] as TextContent).text)
                val ids =
                    Json
                        .parseToJsonElement(text)
                        .jsonObject["nodes"]!!
                        .jsonArray
                        .map { it.jsonObject["node_id"]!!.jsonPrimitive.content }
                assertEquals(listOf("node_on"), ids)
            }
        }

    @Test
    fun `click_node with non-existent node_id returns node not found error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_xyz", any<List<WindowData>>())
            } returns Result.failure(NoSuchElementException("Node 'node_xyz' not found"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_node",
                        arguments = mapOf("node_id" to "node_xyz"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("node_xyz"))
            }
        }

    @Test
    fun `tap_node with valid node_id taps within bounds and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_btn")
            } returns sampleTree.children[0]
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_tap_node",
                        arguments = mapOf("node_id" to "node_btn"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Tap executed"))
                assertTrue(text.contains("node_btn"))
            }
        }

    @Test
    fun `tap_node with non-existent node_id returns node not found error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            every {
                deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_xyz")
            } returns null

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_tap_node",
                        arguments = mapOf("node_id" to "node_xyz"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("node_xyz"))
            }
        }

    @Nested
    @DisplayName("Multi-Window Scenarios")
    inner class MultiWindowScenarios {
        private val sampleScreenInfo =
            ScreenInfo(
                width = 1080,
                height = 2400,
                densityDpi = 420,
                orientation = "portrait",
            )

        private val dialogTree =
            AccessibilityNodeData(
                id = "node_root_w99",
                className = "android.widget.FrameLayout",
                bounds = BoundsData(0, 0, 1080, 2400),
                visible = true,
                enabled = true,
                children =
                    listOf(
                        AccessibilityNodeData(
                            id = "node_allow_w99",
                            className = "android.widget.Button",
                            text = "Allow",
                            bounds = BoundsData(200, 1200, 500, 1300),
                            clickable = true,
                            visible = true,
                            enabled = true,
                        ),
                    ),
            )

        private fun MockDependencies.setupTwoWindowMock() {
            val mockRootApp = mockk<AccessibilityNodeInfo>()
            val mockRootDialog = mockk<AccessibilityNodeInfo>()
            val mockWindowApp = mockk<AccessibilityWindowInfo>(relaxed = true)
            val mockWindowDialog = mockk<AccessibilityWindowInfo>(relaxed = true)

            every { accessibilityServiceProvider.isReady() } returns true
            every { mockWindowApp.id } returns 42
            every { mockWindowApp.root } returns mockRootApp
            every { mockWindowApp.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { mockWindowApp.title } returns "MyApp"
            every { mockWindowApp.layer } returns 0
            every { mockWindowApp.isFocused } returns false
            every { mockRootApp.refresh() } returns true
            every { mockRootApp.packageName } returns "com.example.myapp"

            every { mockWindowDialog.id } returns 99
            every { mockWindowDialog.root } returns mockRootDialog
            every { mockWindowDialog.type } returns AccessibilityWindowInfo.TYPE_SYSTEM
            every { mockWindowDialog.title } returns "Permission"
            every { mockWindowDialog.layer } returns 1
            every { mockWindowDialog.isFocused } returns true
            every { mockRootDialog.refresh() } returns true
            every { mockRootDialog.packageName } returns "com.android.permissioncontroller"

            every {
                accessibilityServiceProvider.getAccessibilityWindows()
            } returns listOf(mockWindowApp, mockWindowDialog)
            every { accessibilityServiceProvider.getCurrentPackageName() } returns "com.example.myapp"
            every { accessibilityServiceProvider.getCurrentActivityName() } returns ".MainActivity"
            every { accessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo

            every { treeParser.parseTree(mockRootApp, "root_w42", any()) } returns sampleTree
            every { treeParser.parseTree(mockRootDialog, "root_w99", any()) } returns dialogTree
        }

        @Test
        fun `find_nodes finds node in system dialog window`() =
            runTest {
                val deps = McpIntegrationTestHelper.createMockDependencies()
                deps.setupTwoWindowMock()
                every {
                    deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "Allow", false)
                } returns
                    listOf(
                        ElementInfo(
                            id = "node_allow_w99",
                            text = "Allow",
                            className = "android.widget.Button",
                            bounds = BoundsData(200, 1200, 500, 1300),
                            clickable = true,
                            enabled = true,
                            visible = true,
                        ),
                    )

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_find_nodes",
                            arguments = mapOf("by" to "text", "value" to "Allow"),
                        )
                    assertNotEquals(true, result.isError)
                    val textContent = (result.content[0] as TextContent).text
                    val parsed = Json.parseToJsonElement(stripUntrustedWarning(textContent)).jsonObject
                    val elements = parsed["nodes"]!!.jsonArray
                    assertEquals(1, elements.size)
                    assertEquals(
                        "node_allow_w99",
                        elements[0].jsonObject["node_id"]?.jsonPrimitive?.content,
                    )
                }
            }

        @Test
        fun `click_node clicks node in non-primary window`() =
            runTest {
                val deps = McpIntegrationTestHelper.createMockDependencies()
                deps.setupTwoWindowMock()
                coEvery {
                    deps.actionExecutor.clickNode("node_allow_w99", any<List<WindowData>>())
                } returns Result.success(Unit)

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_click_node",
                            arguments = mapOf("node_id" to "node_allow_w99"),
                        )
                    assertNotEquals(true, result.isError)
                    assertTrue(result.content.isNotEmpty())
                }
            }
    }
}
