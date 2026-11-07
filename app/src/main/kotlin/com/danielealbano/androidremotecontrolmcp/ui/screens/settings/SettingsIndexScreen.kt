@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarTitle
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsIndexScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { TopBarTitle(stringResource(R.string.tab_settings)) },
            windowInsets = WindowInsets(0),
        )
        SettingsEntriesColumn(
            onNavigate = onNavigate,
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        )
    }
}

private class SettingsIndexEntry(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val route: String,
)

private val SETTINGS_INDEX_ENTRIES =
    listOf(
        SettingsIndexEntry(
            icon = Icons.Default.Tune,
            titleRes = R.string.settings_general_title,
            subtitleRes = R.string.settings_general_subtitle,
            route = SettingsRoute.General.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Key,
            titleRes = R.string.settings_access_title,
            subtitleRes = R.string.settings_access_subtitle,
            route = SettingsRoute.Access.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Lock,
            titleRes = R.string.settings_security_title,
            subtitleRes = R.string.settings_security_subtitle,
            route = SettingsRoute.Security.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Shield,
            titleRes = R.string.settings_privacy_title,
            subtitleRes = R.string.settings_privacy_subtitle,
            route = SettingsRoute.Privacy.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Cloud,
            titleRes = R.string.settings_tunnel_title,
            subtitleRes = R.string.settings_tunnel_subtitle,
            route = SettingsRoute.Tunnel.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Build,
            titleRes = R.string.settings_mcp_tools_title,
            subtitleRes = R.string.settings_mcp_tools_subtitle,
            route = SettingsRoute.McpTools.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Terminal,
            titleRes = R.string.settings_shell_title,
            subtitleRes = R.string.settings_shell_subtitle,
            route = SettingsRoute.Shell.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.AdminPanelSettings,
            titleRes = R.string.settings_permissions_title,
            subtitleRes = R.string.settings_permissions_subtitle,
            route = SettingsRoute.Permissions.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.Folder,
            titleRes = R.string.settings_storage_title,
            subtitleRes = R.string.settings_storage_subtitle,
            route = SettingsRoute.Storage.route,
        ),
        SettingsIndexEntry(
            icon = Icons.Default.CellTower,
            titleRes = R.string.event_channel_name,
            subtitleRes = R.string.event_channel_subtitle,
            route = SettingsRoute.ChannelSettings.route,
        ),
    )

@Composable
private fun SettingsEntriesColumn(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SETTINGS_INDEX_ENTRIES.forEach { entry ->
            SettingsEntry(
                icon = entry.icon,
                title = stringResource(entry.titleRes),
                subtitle = stringResource(entry.subtitleRes),
                onClick = { onNavigate(entry.route) },
            )
        }
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.a11y_action_open), onClick = onClick),
    )
}
