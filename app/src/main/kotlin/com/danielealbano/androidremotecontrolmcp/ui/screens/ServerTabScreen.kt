@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danielealbano.androidremotecontrolmcp.ui.navigation.ServerRoute
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@Composable
fun ServerTabScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onNavigateToTunnelSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ServerRoute.Index.route,
        modifier = modifier,
    ) {
        composable(ServerRoute.Index.route) {
            ServerScreen(
                onNavigateToPermissions = onNavigateToPermissions,
                onShowAllLogs = { navController.navigate(ServerRoute.Logs.route) },
                onNavigateToNetworkSettings = onNavigateToNetworkSettings,
                onNavigateToTunnelSettings = onNavigateToTunnelSettings,
                onOpenPrivacySettings = onOpenPrivacySettings,
                viewModel = viewModel,
                arcTunnelViewModel = arcTunnelViewModel,
            )
        }
        composable(ServerRoute.Logs.route) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
