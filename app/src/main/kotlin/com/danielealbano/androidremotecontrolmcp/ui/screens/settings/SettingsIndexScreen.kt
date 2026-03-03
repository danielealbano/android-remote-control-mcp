@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
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
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsIndexScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsEntry(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.settings_general_title),
                subtitle = stringResource(R.string.settings_general_subtitle),
                onClick = { onNavigate(SettingsRoute.General.route) },
            )
            SettingsEntry(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_security_title),
                subtitle = stringResource(R.string.settings_security_subtitle),
                onClick = { onNavigate(SettingsRoute.Security.route) },
            )
            SettingsEntry(
                icon = Icons.Default.Cloud,
                title = stringResource(R.string.settings_tunnel_title),
                subtitle = stringResource(R.string.settings_tunnel_subtitle),
                onClick = { onNavigate(SettingsRoute.Tunnel.route) },
            )
            SettingsEntry(
                icon = Icons.Default.Build,
                title = stringResource(R.string.settings_mcp_tools_title),
                subtitle = stringResource(R.string.settings_mcp_tools_subtitle),
                onClick = { onNavigate(SettingsRoute.McpTools.route) },
            )
            SettingsEntry(
                icon = Icons.Default.AdminPanelSettings,
                title = stringResource(R.string.settings_permissions_title),
                subtitle = stringResource(R.string.settings_permissions_subtitle),
                onClick = { onNavigate(SettingsRoute.Permissions.route) },
            )
            SettingsEntry(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.settings_storage_title),
                subtitle = stringResource(R.string.settings_storage_subtitle),
                onClick = { onNavigate(SettingsRoute.Storage.route) },
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
        modifier = Modifier.clickable(onClick = onClick),
    )
}
