@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
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
                        name = "press_back",
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
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            coEvery {
                deps.actionExecutor.clickNode("nonexistent", sampleTree)
            } returns
                Result.failure(
                    NoSuchElementException("Node 'nonexistent' not found"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "click_element",
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
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            coEvery {
                deps.actionExecutor.clickNode("node_root", sampleTree)
            } returns
                Result.failure(
                    IllegalStateException("Node 'node_root' is not clickable"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "click_element",
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
                        name = "tap",
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
                        name = "press_back",
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
                val mockRootNode = mockk<AccessibilityNodeInfo>()
                every { deps.accessibilityServiceProvider.isReady() } returns true
                every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
                every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
                every { mockRootNode.recycle() } returns Unit
                every {
                    deps.elementFinder.findElements(sampleTree, FindBy.TEXT, "nonexistent_element_xyz", false)
                } answers {
                    clockMs += 600L
                    emptyList()
                }

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "wait_for_element",
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
                val mockRootNode = mockk<AccessibilityNodeInfo>()
                every { deps.accessibilityServiceProvider.isReady() } returns true
                every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
                every { mockRootNode.recycle() } returns Unit

                var callCount = 0
                every { deps.treeParser.parseTree(mockRootNode) } answers {
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
                            name = "wait_for_idle",
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
