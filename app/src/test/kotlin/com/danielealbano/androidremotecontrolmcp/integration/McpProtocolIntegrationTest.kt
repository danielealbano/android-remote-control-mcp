package com.danielealbano.androidremotecontrolmcp.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MCP Protocol Integration Tests")
class McpProtocolIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `client connects successfully and completes initialize handshake`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                // If connect() succeeds, the initialize handshake completed
                assertNotNull(client.serverCapabilities)
                assertNotNull(client.serverVersion)
                assertEquals("android-remote-control-mcp", client.serverVersion?.name)
            }
        }

    @Test
    fun `listTools returns all 29 registered tools`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.listTools()
                assertEquals(EXPECTED_TOOL_COUNT, result.tools.size)
            }
        }

    @Test
    fun `listTools includes correct tool metadata for each tool`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.listTools()

                result.tools.forEach { tool ->
                    assertNotNull(tool.name, "Tool missing name")
                    assertNotNull(tool.description, "Tool ${tool.name} missing description")
                    assertNotNull(tool.inputSchema, "Tool ${tool.name} missing inputSchema")
                }
            }
        }

    @Test
    fun `server capabilities include tools`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                assertNotNull(client.serverCapabilities?.tools)
            }
        }

    @Test
    fun `listTools contains expected tool names`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.listTools()
                val toolNames = result.tools.map { it.name }.toSet()

                EXPECTED_TOOL_NAMES.forEach { expectedName ->
                    assertTrue(
                        toolNames.contains(expectedName),
                        "Expected tool '$expectedName' not found in: $toolNames",
                    )
                }
            }
        }

    companion object {
        private const val EXPECTED_TOOL_COUNT = 29

        private val EXPECTED_TOOL_NAMES = setOf(
            // Touch actions
            "tap", "long_press", "double_tap", "swipe", "scroll",
            // Gestures
            "pinch", "custom_gesture",
            // Element actions
            "find_elements", "click_element", "long_click_element",
            "set_text", "scroll_to_element",
            // Screen introspection
            "get_accessibility_tree", "capture_screenshot",
            "get_current_app", "get_screen_info",
            // System actions
            "press_back", "press_home", "press_recents",
            "open_notifications", "open_quick_settings",
            "get_device_logs",
            // Text input
            "input_text", "clear_text", "press_key",
            // Utility
            "get_clipboard", "set_clipboard",
            "wait_for_element", "wait_for_idle",
        )
    }
}
