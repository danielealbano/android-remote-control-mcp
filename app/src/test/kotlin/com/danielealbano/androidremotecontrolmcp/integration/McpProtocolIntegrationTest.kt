package com.danielealbano.androidremotecontrolmcp.integration

import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
                assertEquals("android-remote-control-mcp", client.serverVersion?.name) // No slug in default tests
            }
        }

    @Test
    fun `listTools returns all 45 registered tools`() =
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

    @Test
    fun `listTools with device slug includes slug in tool names`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(
                deviceSlug = "pixel7",
            ) { client, _ ->
                val result = client.listTools()
                result.tools.forEach { tool ->
                    assertTrue(
                        tool.name.startsWith("android_pixel7_"),
                        "Tool '${tool.name}' should start with 'android_pixel7_'",
                    )
                }
            }
        }

    @Test
    fun `server name includes device slug when set`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(
                deviceSlug = "pixel7",
            ) { client, _ ->
                assertEquals("android-remote-control-mcp-pixel7", client.serverVersion?.name)
            }
        }

    @Test
    fun `server name without slug uses default`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                assertEquals("android-remote-control-mcp", client.serverVersion?.name)
            }
        }

    @Test
    fun `tool with slug can be called successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(
                deps = deps,
                deviceSlug = "pixel7",
            ) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_pixel7_tap",
                        arguments = mapOf("x" to 100, "y" to 200),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    companion object {
        private const val EXPECTED_TOOL_COUNT = 45

        private val EXPECTED_TOOL_NAMES =
            setOf(
                // Touch actions
                "android_tap",
                "android_long_press",
                "android_double_tap",
                "android_swipe",
                "android_scroll",
                // Gestures
                "android_pinch",
                "android_custom_gesture",
                // Element actions
                "android_find_elements",
                "android_click_element",
                "android_long_click_element",
                "android_scroll_to_element",
                // Screen introspection
                "android_get_screen_state",
                // System actions
                "android_press_back",
                "android_press_home",
                "android_press_recents",
                "android_open_notifications",
                "android_open_quick_settings",
                "android_get_device_logs",
                // Text input
                "android_type_append_text",
                "android_type_insert_text",
                "android_type_replace_text",
                "android_type_clear_text",
                "android_press_key",
                // Utility
                "android_get_clipboard",
                "android_set_clipboard",
                "android_wait_for_element",
                "android_wait_for_idle",
                "android_get_element_details",
                // File tools
                "android_list_storage_locations",
                "android_list_files",
                "android_read_file",
                "android_write_file",
                "android_append_file",
                "android_file_replace",
                "android_download_from_url",
                "android_delete_file",
                // App management
                "android_open_app",
                "android_list_apps",
                "android_close_app",
            )
    }
}
