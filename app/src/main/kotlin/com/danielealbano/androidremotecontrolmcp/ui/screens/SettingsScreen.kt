@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.GeneralSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.McpToolsSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.PermissionsSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SecuritySettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SettingsIndexScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.StorageSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TunnelSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@Composable
fun SettingsScreen(
    onRequestNotificationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) {
                launchSingleTop = true
            }
            onPendingRouteConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = SettingsRoute.Index.route,
        modifier = modifier,
    ) {
        composable(SettingsRoute.Index.route) {
            SettingsIndexScreen(onNavigate = { navController.navigate(it) })
        }
        composable(SettingsRoute.General.route) {
            GeneralSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        composable(SettingsRoute.Security.route) {
            SecuritySettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        composable(SettingsRoute.Tunnel.route) {
            TunnelSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        composable(SettingsRoute.McpTools.route) {
            McpToolsSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.Permissions.route) {
            PermissionsSettingsScreen(
                onBack = { navController.popBackStack() },
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestCameraPermission = onRequestCameraPermission,
                onRequestMicrophonePermission = onRequestMicrophonePermission,
                viewModel = viewModel,
            )
        }
        composable(SettingsRoute.Storage.route) {
            StorageSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
    }
}
