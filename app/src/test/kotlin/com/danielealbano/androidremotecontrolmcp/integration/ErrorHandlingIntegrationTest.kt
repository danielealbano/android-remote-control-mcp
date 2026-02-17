@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.any
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

@DisplayName("Error Handling Integration Tests")
class ErrorHandlingIntegrationTest {
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
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
    fun `permission denied returns error result`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_press_back",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Accessibility service not enabled"))
            }
        }

    @Test
    fun `element not found returns error result`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("nonexistent", any<List<WindowData>>())
            } returns
                Result.failure(
                    NoSuchElementException("Node 'nonexistent' not found"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_element",
                        arguments = mapOf("element_id" to "nonexistent"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("nonexistent"))
            }
        }

    @Test
    fun `action failed returns error result`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
            coEvery {
                deps.actionExecutor.clickNode("node_root", any<List<WindowData>>())
            } returns
                Result.failure(
                    IllegalStateException("Node 'node_root' is not clickable"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_element",
                        arguments = mapOf("element_id" to "node_root"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("not clickable"))
            }
        }

    @Test
    fun `invalid params returns error result`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_tap",
                        arguments = mapOf("x" to "not_a_number", "y" to 100),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.isNotEmpty())
            }
        }

    @Test
    fun `internal error returns error result`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery {
                deps.actionExecutor.pressBack()
            } throws RuntimeException("Unexpected internal error")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_press_back",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Unexpected internal error"))
            }
        }

    @Test
    fun `wait_for_element timeout returns informational non-error result`() =
        runTest {
            mockkStatic(SystemClock::class)
            try {
                var clockMs = 0L
                every { SystemClock.elapsedRealtime() } answers { clockMs }

                val deps = McpIntegrationTestHelper.createMockDependencies()
                McpIntegrationTestHelper.setupMultiWindowMock(deps, sampleTree, sampleScreenInfo)
                every {
                    deps.elementFinder.findElements(
                        any<List<WindowData>>(),
                        FindBy.TEXT,
                        "nonexistent_element_xyz",
                        false,
                    )
                } answers {
                    clockMs += 600L
                    emptyList()
                }

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_wait_for_element",
                            arguments =
                                mapOf(
                                    "by" to "text",
                                    "value" to "nonexistent_element_xyz",
                                    "timeout" to 1000,
                                ),
                        )
                    assertNotEquals(true, result.isError)
                    val text = (result.content[0] as TextContent).text
                    assertTrue(text.contains("timed out"))
                }
            } finally {
                unmockkStatic(SystemClock::class)
            }
        }

    @Test
    fun `wait_for_idle timeout returns informational non-error result`() =
        runTest {
            mockkStatic(SystemClock::class)
            try {
                var clockMs = 0L
                every { SystemClock.elapsedRealtime() } answers { clockMs }

                val deps = McpIntegrationTestHelper.createMockDependencies()
                every { deps.accessibilityServiceProvider.isReady() } returns true
                val mockRootNode = mockk<android.view.accessibility.AccessibilityNodeInfo>()
                val mockWin = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWin.id } returns 0
                every { mockWin.root } returns mockRootNode
                every { mockWin.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
                every { mockWin.title } returns "Test"
                every { mockWin.layer } returns 0
                every { mockWin.isFocused } returns true
                every { mockRootNode.packageName } returns "com.example"
                every {
                    deps.accessibilityServiceProvider.getAccessibilityWindows()
                } returns listOf(mockWin)
                every { deps.accessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
                every { deps.accessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
                every { deps.accessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
                @Suppress("DEPRECATION")
                every { mockRootNode.recycle() } returns Unit

                var callCount = 0
                every { deps.treeParser.parseTree(any(), any()) } answers {
                    callCount++
                    clockMs += 600L
                    AccessibilityNodeData(
                        id = "node_root",
                        className = "android.widget.FrameLayout",
                        text = "changing_text_$callCount",
                        bounds = BoundsData(0, 0, 1080, 2400),
                        visible = true,
                    )
                }

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_wait_for_idle",
                            arguments = mapOf("timeout" to 1000),
                        )
                    assertNotEquals(true, result.isError)
                    val text = (result.content[0] as TextContent).text
                    val parsed = Json.parseToJsonElement(text).jsonObject
                    assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("timed out") == true)
                    assertTrue(parsed.containsKey("similarity"))
                    assertTrue(parsed.containsKey("elapsedMs"))
                }
            } finally {
                unmockkStatic(SystemClock::class)
            }
        }
}
