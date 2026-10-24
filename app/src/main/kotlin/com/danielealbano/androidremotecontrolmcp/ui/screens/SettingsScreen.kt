@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.SettingsRoute
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.AccessSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.ChannelSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.GeneralSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.McpToolsSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.NotificationFilterScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.OAuthClientsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.PermissionsSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.PrivacySettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SecuritySettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.SettingsIndexScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.StorageSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.TunnelSettingsScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.WifiMonitorScreen
import com.danielealbano.androidremotecontrolmcp.ui.screens.settings.geofenceDestinations
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@Composable
fun SettingsScreen(
    onRequestNotificationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),
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
        composable(SettingsRoute.Access.route) {
            AccessSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateClients = { navController.navigate(SettingsRoute.OAuthClients.route) },
            )
        }
        composable(SettingsRoute.OAuthClients.route) {
            OAuthClientsScreen(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.Security.route) {
            SecuritySettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        composable(SettingsRoute.Privacy.route) {
            PrivacySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.Tunnel.route) {
            TunnelSettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
                arcViewModel = arcTunnelViewModel,
            )
        }
        composable(SettingsRoute.McpTools.route) {
            McpToolsSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPermissions = { navController.navigate(SettingsRoute.Permissions.route) },
            )
        }
        composable(SettingsRoute.Permissions.route) {
            PermissionsSettingsScreen(
                onBack = { navController.popBackStack() },
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestCameraPermission = onRequestCameraPermission,
                onRequestMicrophonePermission = onRequestMicrophonePermission,
                onRequestLocationPermission = onRequestLocationPermission,
                viewModel = viewModel,
            )
        }
        composable(SettingsRoute.Storage.route) {
            StorageSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
        }
        composable(SettingsRoute.ChannelSettings.route) {
            ChannelSettingsScreen(
                viewModel = channelViewModel,
                navController = navController,
                onNavigateToNotificationFilter = {
                    navController.navigate(SettingsRoute.NotificationFilter.route)
                },
                onNavigateToWifiMonitor = {
                    navController.navigate(SettingsRoute.WifiMonitor.route)
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(SettingsRoute.NotificationFilter.route) {
            NotificationFilterScreen(
                viewModel = channelViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(SettingsRoute.WifiMonitor.route) {
            WifiMonitorScreen(
                viewModel = channelViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        geofenceDestinations(navController)
    }
}
