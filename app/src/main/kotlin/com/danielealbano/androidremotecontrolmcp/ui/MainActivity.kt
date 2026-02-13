package com.danielealbano.androidremotecontrolmcp.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.danielealbano.androidremotecontrolmcp.ui.screens.HomeScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        setContent {
            AndroidRemoteControlMcpTheme {
                HomeScreen(
                    onRequestNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus(this)
    }
    // NOTE: No onPause() needed for broadcast receiver since we use StateFlow
    // for server status, which is collected in MainViewModel via viewModelScope.

    /**
     * Requests the POST_NOTIFICATIONS runtime permission (Android 13+).
     *
     * On Android 12 and below, notification permission is always granted so
     * this is a no-op — the UI will already show the permission as granted.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
