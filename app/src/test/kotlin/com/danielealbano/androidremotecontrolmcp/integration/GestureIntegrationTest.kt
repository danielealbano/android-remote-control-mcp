package com.danielealbano.androidremotecontrolmcp.integration

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

@DisplayName("Gesture Integration Tests")
class GestureIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `pinch with valid parameters calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.actionExecutor.pinch(540f, 1200f, 2.0f, any())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(
                    name = "pinch",
                    arguments = mapOf(
                        "center_x" to 540,
                        "center_y" to 1200,
                        "scale" to 2.0,
                    ),
                )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
            }
        }

    @Test
    fun `pinch with invalid scale returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.callTool(
                    name = "pinch",
                    arguments = mapOf(
                        "center_x" to 540,
                        "center_y" to 1200,
                        "scale" to 0.0,
                    ),
                )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("scale"))
            }
        }
}
