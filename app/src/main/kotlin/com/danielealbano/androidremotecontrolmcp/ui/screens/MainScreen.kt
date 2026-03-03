@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.navigation.TopLevelRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@Composable
fun MainScreen(
    onRequestNotificationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    var selectedTabRoute by rememberSaveable { mutableStateOf(TopLevelRoute.Server.route) }
    var pendingSettingsRoute by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(TopLevelRoute.Server, Icons.Default.Dns, stringResource(R.string.tab_server)),
                    Triple(TopLevelRoute.Settings, Icons.Default.Settings, stringResource(R.string.tab_settings)),
                    Triple(TopLevelRoute.About, Icons.Default.Info, stringResource(R.string.tab_about)),
                ).forEach { (route, icon, label) ->
                    NavigationBarItem(
                        selected = selectedTabRoute == route.route,
                        onClick = { selectedTabRoute = route.route },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (selectedTabRoute) {
            TopLevelRoute.Server.route ->
                ServerScreen(
                    onNavigateToPermissions = {
                        pendingSettingsRoute = SettingsRoute.Permissions.route
                        selectedTabRoute = TopLevelRoute.Settings.route
                    },
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                )
            TopLevelRoute.Settings.route ->
                SettingsScreen(
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onRequestMicrophonePermission = onRequestMicrophonePermission,
                    pendingRoute = pendingSettingsRoute,
                    onPendingRouteConsumed = { pendingSettingsRoute = null },
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                )
            TopLevelRoute.About.route -> AboutScreen(modifier = Modifier.padding(paddingValues))
            else ->
                ServerScreen(
                    onNavigateToPermissions = {
                        pendingSettingsRoute = SettingsRoute.Permissions.route
                        selectedTabRoute = TopLevelRoute.Settings.route
                    },
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                )
        }
    }
}
