package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import io.mockk.coEvery
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Touch Action Integration Tests")
class TouchActionIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `tap with valid coordinates calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(500f, 800f) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_tap",
                        arguments = mapOf("x" to 500, "y" to 800),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
            }
        }

    @Test
    fun `tap with missing x coordinate returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_tap",
                        arguments = mapOf("y" to 800),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.isNotEmpty())
            }
        }

    @Test
    fun `swipe with valid coordinates calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.actionExecutor.swipe(100f, 200f, 300f, 400f, any())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_swipe",
                        arguments =
                            mapOf(
                                "x1" to 100,
                                "y1" to 200,
                                "x2" to 300,
                                "y2" to 400,
                            ),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Swipe executed"))
            }
        }

    @Test
    fun `scroll down with default params calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.actionExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM, any())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_scroll",
                        arguments = mapOf("direction" to "down"),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Scroll down"))
            }
        }

    @Test
    fun `scroll with custom variance passes correct variancePercent to actionExecutor`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.actionExecutor.scroll(ScrollDirection.UP, ScrollAmount.SMALL, 0.10f)
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_scroll",
                        arguments =
                            mapOf(
                                "direction" to "up",
                                "amount" to "small",
                                "variance" to 10,
                            ),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Scroll up"))
            }
        }

    @Test
    fun `scroll with variance exceeding max returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_scroll",
                        arguments =
                            mapOf(
                                "direction" to "down",
                                "variance" to 21,
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("variance"))
            }
        }

    @Test
    fun `scroll with invalid direction returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_scroll",
                        arguments = mapOf("direction" to "diagonal"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("direction"))
            }
        }
}
