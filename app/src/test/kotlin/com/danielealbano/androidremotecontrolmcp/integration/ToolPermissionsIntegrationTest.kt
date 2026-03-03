package com.danielealbano.androidremotecontrolmcp.integration

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.camera.VideoRecordingResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Tool Permissions Integration Tests")
class ToolPermissionsIntegrationTest {
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            enabled = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_btn",
                        className = "android.widget.Button",
                        text = "OK",
                        bounds = BoundsData(100, 200, 300, 260),
                        clickable = true,
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
    fun `disabled tool absent from tool list`() =
        runTest {
            val perms = ToolPermissionsConfig(disabledTools = setOf("tap"))

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                val toolNames = result.tools.map { it.name }.toSet()

                assertFalse(
                    toolNames.contains("android_tap"),
                    "Disabled tool 'tap' should not be in tool list",
                )
                assertTrue(
                    toolNames.contains("android_long_press"),
                    "Enabled tool 'long_press' should be in tool list",
                )
            }
        }

    @Test
    fun `all tools disabled returns empty tool list`() =
        runTest {
            val allToolNames =
                setOf(
                    "tap",
                    "long_press",
                    "double_tap",
                    "swipe",
                    "scroll",
                    "press_back",
                    "press_home",
                    "press_recents",
                    "open_notifications",
                    "open_quick_settings",
                    "get_device_logs",
                    "pinch",
                    "custom_gesture",
                    "find_elements",
                    "click_element",
                    "long_click_element",
                    "scroll_to_element",
                    "type_append_text",
                    "type_insert_text",
                    "type_replace_text",
                    "type_clear_text",
                    "press_key",
                    "get_clipboard",
                    "set_clipboard",
                    "wait_for_element",
                    "wait_for_idle",
                    "get_element_details",
                    "list_storage_locations",
                    "list_files",
                    "read_file",
                    "write_file",
                    "append_file",
                    "file_replace",
                    "download_from_url",
                    "delete_file",
                    "open_app",
                    "list_apps",
                    "close_app",
                    "list_cameras",
                    "list_camera_photo_resolutions",
                    "list_camera_video_resolutions",
                    "take_camera_photo",
                    "save_camera_photo",
                    "save_camera_video",
                    "send_intent",
                    "open_uri",
                    "notification_list",
                    "notification_open",
                    "notification_dismiss",
                    "notification_snooze",
                    "notification_action",
                    "notification_reply",
                    "get_screen_state",
                )
            val perms = ToolPermissionsConfig(disabledTools = allToolNames)

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                assertEquals(
                    0,
                    result.tools.size,
                    "All tools disabled should result in empty tool list",
                )
            }
        }

    @Test
    fun `include_screenshot absent from schema when disabled`() =
        runTest {
            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
                )

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                val tool = result.tools.find { it.name == "android_get_screen_state" }
                assertTrue(tool != null, "get_screen_state tool should be registered")

                val properties = tool!!.inputSchema?.properties
                assertFalse(
                    properties?.containsKey("include_screenshot") == true,
                    "include_screenshot should be absent from schema when disabled",
                )
            }
        }

    @Test
    fun `audio absent from schema when disabled`() =
        runTest {
            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("save_camera_video" to setOf("audio")),
                )

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result = client.listTools()
                val tool = result.tools.find { it.name == "android_save_camera_video" }
                assertTrue(tool != null, "save_camera_video tool should be registered")

                val properties = tool!!.inputSchema?.properties
                assertFalse(
                    properties?.containsKey("audio") == true,
                    "audio should be absent from schema when disabled",
                )
                // Other properties should still be present
                assertTrue(
                    properties?.containsKey("camera_id") == true,
                    "camera_id should still be in schema",
                )
                assertTrue(
                    properties?.containsKey("duration") == true,
                    "duration should still be in schema",
                )
            }
        }

    @Test
    fun `disabled tool call returns error`() =
        runTest {
            val perms = ToolPermissionsConfig(disabledTools = setOf("tap"))

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_tap",
                        arguments = mapOf("x" to 500, "y" to 800),
                    )
                assertEquals(true, result.isError, "Calling a disabled tool should return error")
            }
        }

    @Test
    fun `include_screenshot enforced at execution time`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setupMultiWindowMock(
                deps = deps,
                tree = sampleTree,
                screenInfo = sampleScreenInfo,
            )

            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
                )

            McpIntegrationTestHelper.withTestApplication(deps, perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_screen_state",
                        arguments = mapOf("include_screenshot" to true),
                    )
                assertNotEquals(true, result.isError)
                // Should return text-only (1 content item), not text+image (2 items)
                assertEquals(
                    1,
                    result.content.size,
                    "Should return text-only when include_screenshot is disabled",
                )
                assertTrue(result.content[0] is TextContent)

                // Verify screenshot capture was never called
                coVerify(exactly = 0) {
                    deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }
        }

    @Test
    fun `audio enforced at execution time`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockUri = mockk<Uri>()
            coEvery {
                deps.fileOperationProvider.createFileUri("loc1", "video.mp4", "video/mp4")
            } returns mockUri

            coEvery {
                deps.cameraProvider.saveVideo(
                    cameraId = "0",
                    outputUri = mockUri,
                    durationSeconds = 5,
                    width = null,
                    height = null,
                    audio = false,
                    flashMode = any(),
                )
            } returns
                VideoRecordingResult(
                    fileSizeBytes = 54321L,
                    durationMs = 5000L,
                    thumbnailData = "thumbdata",
                    thumbnailWidth = 320,
                    thumbnailHeight = 240,
                )

            val perms =
                ToolPermissionsConfig(
                    disabledParams = mapOf("save_camera_video" to setOf("audio")),
                )

            McpIntegrationTestHelper.withTestApplication(deps, perms = perms) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_save_camera_video",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "loc1",
                                "path" to "video.mp4",
                                "duration" to 5,
                                "audio" to true,
                            ),
                    )
                assertNotEquals(true, result.isError)

                // Verify audio was forced to false despite client sending true
                coVerify {
                    deps.cameraProvider.saveVideo(
                        cameraId = "0",
                        outputUri = mockUri,
                        durationSeconds = 5,
                        width = null,
                        height = null,
                        audio = false,
                        flashMode = any(),
                    )
                }
            }
        }
}
