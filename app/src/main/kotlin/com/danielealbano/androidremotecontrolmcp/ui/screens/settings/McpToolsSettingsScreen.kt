@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermission
import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarBackButton
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarTitle
import com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

private data class ParamEntry(
    val paramName: String,
    @StringRes val displayName: Int,
)

private data class ToolEntry(
    val toolName: String,
    @StringRes val displayName: Int,
    val params: List<ParamEntry> = emptyList(),
)

private data class ToolCategory(
    @StringRes val header: Int,
    val tools: List<ToolEntry>,
)

private val ALL_TOOL_CATEGORIES: List<ToolCategory> =
    listOf(
        ToolCategory(
            R.string.mcp_tools_category_screen,
            listOf(
                ToolEntry(
                    "get_screen_state",
                    R.string.mcp_tool_get_screen_state,
                    listOf(ParamEntry("include_screenshot", R.string.mcp_tool_param_include_screenshot)),
                ),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_system,
            listOf(
                ToolEntry("press_back", R.string.mcp_tool_press_back),
                ToolEntry("press_home", R.string.mcp_tool_press_home),
                ToolEntry("press_recents", R.string.mcp_tool_press_recents),
                ToolEntry("open_notifications", R.string.mcp_tool_open_notifications),
                ToolEntry("open_quick_settings", R.string.mcp_tool_open_quick_settings),
                ToolEntry("dismiss_keyboard", R.string.mcp_tool_dismiss_keyboard),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_touch,
            listOf(
                ToolEntry("tap", R.string.mcp_tool_tap),
                ToolEntry("long_press", R.string.mcp_tool_long_press),
                ToolEntry("double_tap", R.string.mcp_tool_double_tap),
                ToolEntry("swipe", R.string.mcp_tool_swipe),
                ToolEntry("scroll", R.string.mcp_tool_scroll),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_gestures,
            listOf(
                ToolEntry("pinch", R.string.mcp_tool_pinch),
                ToolEntry("custom_gesture", R.string.mcp_tool_custom_gesture),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_node_actions,
            listOf(
                ToolEntry("find_nodes", R.string.mcp_tool_find_nodes),
                ToolEntry("click_node", R.string.mcp_tool_click_node),
                ToolEntry("long_click_node", R.string.mcp_tool_long_click_node),
                ToolEntry("perform_node_action", R.string.mcp_tool_perform_node_action),
                ToolEntry("tap_node", R.string.mcp_tool_tap_node),
                ToolEntry("scroll_to_node", R.string.mcp_tool_scroll_to_node),
                ToolEntry("scroll_node", R.string.mcp_tool_scroll_node),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_text_input,
            listOf(
                ToolEntry("type_append_text", R.string.mcp_tool_type_append_text),
                ToolEntry("type_insert_text", R.string.mcp_tool_type_insert_text),
                ToolEntry("type_replace_text", R.string.mcp_tool_type_replace_text),
                ToolEntry("type_clear_text", R.string.mcp_tool_type_clear_text),
                ToolEntry("press_key", R.string.mcp_tool_press_key),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_utility,
            listOf(
                ToolEntry("get_clipboard", R.string.mcp_tool_get_clipboard),
                ToolEntry("set_clipboard", R.string.mcp_tool_set_clipboard),
                ToolEntry("wait_for_node", R.string.mcp_tool_wait_for_node),
                ToolEntry("wait_for_idle", R.string.mcp_tool_wait_for_idle),
                ToolEntry("get_node_details", R.string.mcp_tool_get_node_details),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_file_operations,
            listOf(
                ToolEntry("list_storage_locations", R.string.mcp_tool_list_storage_locations),
                ToolEntry("list_files", R.string.mcp_tool_list_files),
                ToolEntry("read_file", R.string.mcp_tool_read_file),
                ToolEntry("write_file", R.string.mcp_tool_write_file),
                ToolEntry("append_file", R.string.mcp_tool_append_file),
                ToolEntry("file_replace", R.string.mcp_tool_file_replace),
                ToolEntry("download_from_url", R.string.mcp_tool_download_from_url),
                ToolEntry("delete_file", R.string.mcp_tool_delete_file),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_app_management,
            listOf(
                ToolEntry("open_app", R.string.mcp_tool_open_app),
                ToolEntry("list_apps", R.string.mcp_tool_list_apps),
                ToolEntry("close_app", R.string.mcp_tool_close_app),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_camera,
            listOf(
                ToolEntry("list_cameras", R.string.mcp_tool_list_cameras),
                ToolEntry("list_camera_photo_resolutions", R.string.mcp_tool_list_camera_photo_resolutions),
                ToolEntry("list_camera_video_resolutions", R.string.mcp_tool_list_camera_video_resolutions),
                ToolEntry("take_camera_photo", R.string.mcp_tool_take_camera_photo),
                ToolEntry("save_camera_photo", R.string.mcp_tool_save_camera_photo),
                ToolEntry(
                    "save_camera_video",
                    R.string.mcp_tool_save_camera_video,
                    listOf(ParamEntry("audio", R.string.mcp_tool_param_audio)),
                ),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_intent,
            listOf(
                ToolEntry("send_intent", R.string.mcp_tool_send_intent),
                ToolEntry("open_uri", R.string.mcp_tool_open_uri),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_notifications,
            listOf(
                ToolEntry("notification_list", R.string.mcp_tool_notification_list),
                ToolEntry("notification_open", R.string.mcp_tool_notification_open),
                ToolEntry("notification_dismiss", R.string.mcp_tool_notification_dismiss),
                ToolEntry("notification_snooze", R.string.mcp_tool_notification_snooze),
                ToolEntry("notification_action", R.string.mcp_tool_notification_action),
                ToolEntry("notification_reply", R.string.mcp_tool_notification_reply),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_location,
            listOf(
                ToolEntry(
                    "get_location",
                    R.string.mcp_tool_get_location,
                    listOf(ParamEntry("fresh_fix", R.string.mcp_tool_param_fresh_fix)),
                ),
            ),
        ),
        ToolCategory(
            R.string.mcp_tools_category_sharing,
            listOf(
                ToolEntry("get_shared_content", R.string.mcp_tool_get_shared_content),
                ToolEntry("share_file_via_web", R.string.mcp_tool_share_file_via_web),
            ),
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsSettingsScreen(
    onBack: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val perms by viewModel.toolPermissionsConfig.collectAsStateWithLifecycle()
    val cameraGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()
    val locationGranted by viewModel.isLocationPermissionGranted.collectAsStateWithLifecycle()
    val notificationListenerGranted by viewModel.isNotificationListenerEnabled.collectAsStateWithLifecycle()
    val microphoneGranted by viewModel.isMicrophonePermissionGranted.collectAsStateWithLifecycle()
    val controlsEnabled = serverStatus !is ServerStatus.Running && serverStatus !is ServerStatus.Starting

    // Refresh permissions on ON_RESUME — SAME pattern as PermissionsSettingsScreen.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPermissionStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isGranted: (OptionalToolPermission) -> Boolean = { permission ->
        isPermissionGranted(
            permission = permission,
            camera = cameraGranted,
            location = locationGranted,
            notificationListener = notificationListenerGranted,
            microphone = microphoneGranted,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { TopBarTitle(stringResource(R.string.settings_mcp_tools_title)) },
            navigationIcon = { TopBarBackButton(onClick = onBack) },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    text = stringResource(R.string.settings_mcp_tools_restart_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ALL_TOOL_CATEGORIES.forEach { category ->
                val categoryPermissions =
                    category.tools.mapNotNull { OptionalToolPermissions.permissionForTool(it.toolName) }.distinct()
                item(key = "header_${category.header}") {
                    ToolCategoryHeader(
                        header = stringResource(category.header),
                        missingPermission = categoryPermissions.any { !isGranted(it) },
                        onNavigateToPermissions = onNavigateToPermissions,
                    )
                }
                items(category.tools, key = { it.toolName }) { tool ->
                    ToolRow(
                        tool = tool,
                        perms = perms,
                        controlsEnabled = controlsEnabled,
                        categoryGranted = categoryPermissions.all { isGranted(it) },
                        isGranted = isGranted,
                        onNavigateToPermissions = onNavigateToPermissions,
                        onToolToggle = viewModel::updateToolEnabled,
                        onParamToggle = viewModel::updateParamEnabled,
                    )
                }
            }
        }
    }
}

/** Resolves whether an optional permission is granted from the individual permission flags. */
private fun isPermissionGranted(
    permission: OptionalToolPermission,
    camera: Boolean,
    location: Boolean,
    notificationListener: Boolean,
    microphone: Boolean,
): Boolean =
    when (permission) {
        OptionalToolPermission.CAMERA -> camera
        OptionalToolPermission.LOCATION -> location
        OptionalToolPermission.NOTIFICATION_LISTENER -> notificationListener
        OptionalToolPermission.MICROPHONE -> microphone
    }

@Composable
private fun ToolCategoryHeader(
    header: String,
    missingPermission: Boolean,
    onNavigateToPermissions: () -> Unit,
) {
    // Match the warning triangle to the header text size (respects font scaling).
    val warningIconSize =
        with(LocalDensity.current) {
            MaterialTheme.typography.titleMedium.fontSize
                .toDp()
        }
    if (!missingPermission) {
        Text(
            text = header,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        return
    }
    Column(
        modifier =
            Modifier.clickable(onClickLabel = stringResource(R.string.a11y_action_open_permissions)) {
                onNavigateToPermissions()
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(warningIconSize),
            )
        }
        Text(
            text = stringResource(R.string.settings_mcp_tools_missing_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ToolRow(
    tool: ToolEntry,
    perms: ToolPermissionsConfig,
    controlsEnabled: Boolean,
    categoryGranted: Boolean,
    isGranted: (OptionalToolPermission) -> Boolean,
    onNavigateToPermissions: () -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    onParamToggle: (String, String, Boolean) -> Unit,
) {
    val toolEnabled = perms.isToolEnabled(tool.toolName)
    ListItem(
        modifier =
            Modifier.toggleable(
                value = toolEnabled,
                enabled = controlsEnabled && categoryGranted,
                role = Role.Switch,
                onValueChange = { onToolToggle(tool.toolName, it) },
            ),
        headlineContent = { Text(stringResource(tool.displayName)) },
        trailingContent = {
            Switch(
                checked = toolEnabled,
                onCheckedChange = null,
                enabled = controlsEnabled && categoryGranted,
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        },
    )
    if (toolEnabled) {
        tool.params.forEach { param ->
            ToolParamRow(
                toolName = tool.toolName,
                param = param,
                perms = perms,
                controlsEnabled = controlsEnabled,
                categoryGranted = categoryGranted,
                isGranted = isGranted,
                onNavigateToPermissions = onNavigateToPermissions,
                onParamToggle = onParamToggle,
            )
        }
    }
}

@Composable
private fun ToolParamRow(
    toolName: String,
    param: ParamEntry,
    perms: ToolPermissionsConfig,
    controlsEnabled: Boolean,
    categoryGranted: Boolean,
    isGranted: (OptionalToolPermission) -> Boolean,
    onNavigateToPermissions: () -> Unit,
    onParamToggle: (String, String, Boolean) -> Unit,
) {
    val paramPermission = OptionalToolPermissions.permissionForParam(toolName, param.paramName)
    val paramGranted = paramPermission == null || isGranted(paramPermission)
    val showParamNote = categoryGranted && !paramGranted
    val leadingIcon: (@Composable () -> Unit)? =
        if (showParamNote) {
            { Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = WarningAmber) }
        } else {
            null
        }
    val supportingNote: (@Composable () -> Unit)? =
        if (showParamNote) {
            {
                Text(
                    text = stringResource(R.string.settings_mcp_tools_param_missing_permission),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        }
    val paramEnabled = perms.isParamEnabled(toolName, param.paramName)
    val switchEnabled = controlsEnabled && categoryGranted && paramGranted
    val onToggle: (Boolean) -> Unit = { onParamToggle(toolName, param.paramName, it) }
    val openPermissions = stringResource(R.string.a11y_action_open_permissions)
    val paramName = stringResource(param.displayName)
    ListItem(
        headlineContent = { Text(paramName) },
        // Without its permission the row opens the permission settings and the switch stands alone, named after the
        // row; otherwise the whole row toggles
        modifier =
            if (showParamNote) {
                Modifier.padding(start = 32.dp).clickable(onClickLabel = openPermissions) { onNavigateToPermissions() }
            } else {
                Modifier.padding(start = 32.dp).toggleable(
                    value = paramEnabled,
                    enabled = switchEnabled,
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
            },
        leadingContent = leadingIcon,
        supportingContent = supportingNote,
        trailingContent = {
            Switch(
                checked = paramEnabled,
                onCheckedChange = if (showParamNote) onToggle else null,
                enabled = switchEnabled,
                modifier =
                    if (showParamNote) {
                        Modifier.semantics { contentDescription = paramName }
                    } else {
                        Modifier.minimumInteractiveComponentSize()
                    },
            )
        },
    )
}
