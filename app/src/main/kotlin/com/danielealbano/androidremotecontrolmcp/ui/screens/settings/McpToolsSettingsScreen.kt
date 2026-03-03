@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

private data class ParamEntry(
    val paramName: String,
    val displayName: String,
)

private data class ToolEntry(
    val toolName: String,
    val displayName: String,
    val params: List<ParamEntry> = emptyList(),
)

private data class ToolCategory(
    val header: String,
    val tools: List<ToolEntry>,
)

private val ALL_TOOL_CATEGORIES: List<ToolCategory> =
    listOf(
        ToolCategory(
            "Screen",
            listOf(
                ToolEntry(
                    "get_screen_state",
                    "Get screen state",
                    listOf(ParamEntry("include_screenshot", "Include screenshot")),
                ),
            ),
        ),
        ToolCategory(
            "System",
            listOf(
                ToolEntry("press_back", "Press Back"),
                ToolEntry("press_home", "Press Home"),
                ToolEntry("press_recents", "Press Recents"),
                ToolEntry("open_notifications", "Open Notifications"),
                ToolEntry("open_quick_settings", "Open Quick Settings"),
                ToolEntry("get_device_logs", "Get Device Logs"),
            ),
        ),
        ToolCategory(
            "Touch",
            listOf(
                ToolEntry("tap", "Tap"),
                ToolEntry("long_press", "Long Press"),
                ToolEntry("double_tap", "Double Tap"),
                ToolEntry("swipe", "Swipe"),
                ToolEntry("scroll", "Scroll"),
            ),
        ),
        ToolCategory(
            "Gestures",
            listOf(
                ToolEntry("pinch", "Pinch"),
                ToolEntry("custom_gesture", "Custom Gesture"),
            ),
        ),
        ToolCategory(
            "Element Actions",
            listOf(
                ToolEntry("find_elements", "Find Elements"),
                ToolEntry("click_element", "Click Element"),
                ToolEntry("long_click_element", "Long Click Element"),
                ToolEntry("scroll_to_element", "Scroll to Element"),
            ),
        ),
        ToolCategory(
            "Text Input",
            listOf(
                ToolEntry("type_append_text", "Type Append Text"),
                ToolEntry("type_insert_text", "Type Insert Text"),
                ToolEntry("type_replace_text", "Type Replace Text"),
                ToolEntry("type_clear_text", "Type Clear Text"),
                ToolEntry("press_key", "Press Key"),
            ),
        ),
        ToolCategory(
            "Utility",
            listOf(
                ToolEntry("get_clipboard", "Get Clipboard"),
                ToolEntry("set_clipboard", "Set Clipboard"),
                ToolEntry("wait_for_element", "Wait for Element"),
                ToolEntry("wait_for_idle", "Wait for Idle"),
                ToolEntry("get_element_details", "Get Element Details"),
            ),
        ),
        ToolCategory(
            "File Operations",
            listOf(
                ToolEntry("list_storage_locations", "List Storage Locations"),
                ToolEntry("list_files", "List Files"),
                ToolEntry("read_file", "Read File"),
                ToolEntry("write_file", "Write File"),
                ToolEntry("append_file", "Append File"),
                ToolEntry("file_replace", "File Replace"),
                ToolEntry("download_from_url", "Download from URL"),
                ToolEntry("delete_file", "Delete File"),
            ),
        ),
        ToolCategory(
            "App Management",
            listOf(
                ToolEntry("open_app", "Open App"),
                ToolEntry("list_apps", "List Apps"),
                ToolEntry("close_app", "Close App"),
            ),
        ),
        ToolCategory(
            "Camera",
            listOf(
                ToolEntry("list_cameras", "List Cameras"),
                ToolEntry("list_camera_photo_resolutions", "List Camera Photo Resolutions"),
                ToolEntry("list_camera_video_resolutions", "List Camera Video Resolutions"),
                ToolEntry("take_camera_photo", "Take Camera Photo"),
                ToolEntry("save_camera_photo", "Save Camera Photo"),
                ToolEntry(
                    "save_camera_video",
                    "Save Camera Video",
                    listOf(ParamEntry("audio", "Include audio")),
                ),
            ),
        ),
        ToolCategory(
            "Intent",
            listOf(
                ToolEntry("send_intent", "Send Intent"),
                ToolEntry("open_uri", "Open URI"),
            ),
        ),
        ToolCategory(
            "Notifications",
            listOf(
                ToolEntry("notification_list", "Notification List"),
                ToolEntry("notification_open", "Notification Open"),
                ToolEntry("notification_dismiss", "Notification Dismiss"),
                ToolEntry("notification_snooze", "Notification Snooze"),
                ToolEntry("notification_action", "Notification Action"),
                ToolEntry("notification_reply", "Notification Reply"),
            ),
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val perms by viewModel.toolPermissionsConfig.collectAsStateWithLifecycle()
    val isEnabled = serverStatus !is ServerStatus.Running && serverStatus !is ServerStatus.Starting

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_mcp_tools_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    text = "Changes take effect on server restart",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ALL_TOOL_CATEGORIES.forEach { category ->
                item(key = "header_${category.header}") {
                    Text(
                        text = category.header,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(category.tools, key = { it.toolName }) { tool ->
                    val toolEnabled = perms.isToolEnabled(tool.toolName)
                    ListItem(
                        headlineContent = { Text(tool.displayName) },
                        trailingContent = {
                            Switch(
                                checked = toolEnabled,
                                onCheckedChange = { viewModel.updateToolEnabled(tool.toolName, it) },
                                enabled = isEnabled,
                            )
                        },
                    )
                    if (toolEnabled) {
                        tool.params.forEach { param ->
                            ListItem(
                                headlineContent = { Text(param.displayName) },
                                modifier = Modifier.padding(start = 32.dp),
                                trailingContent = {
                                    Switch(
                                        checked = perms.isParamEnabled(tool.toolName, param.paramName),
                                        onCheckedChange = {
                                            viewModel.updateParamEnabled(tool.toolName, param.paramName, it)
                                        },
                                        enabled = isEnabled,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
