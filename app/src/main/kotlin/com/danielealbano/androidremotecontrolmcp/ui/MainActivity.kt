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
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckScheduler
import com.danielealbano.androidremotecontrolmcp.ui.screens.MainScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var microphonePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        cameraPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        microphonePermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        locationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        setContent {
            AndroidRemoteControlMcpTheme {
                MainScreen(
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onRequestCameraPermission = ::requestCameraPermission,
                    onRequestMicrophonePermission = ::requestMicrophonePermission,
                    onRequestLocationPermission = ::requestLocationPermission,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opportunistic update check each time the app is brought to the foreground (throttled by
        // WorkManager's unique-work policy). Complements the periodic background check.
        UpdateCheckScheduler.checkOnOpen(this)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus(this)
    }
    // NOTE: No onPause() needed for broadcast receiver since we use StateFlow
    // for server status, which is collected in MainViewModel via viewModelScope.

    /**
     * Requests the POST_NOTIFICATIONS runtime permission.
     *
     * Android 12 (API 31/32) port: POST_NOTIFICATIONS does not exist below API 33,
     * so the request is skipped there (notifications are always allowed on 31/32).
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Requests the CAMERA runtime permission.
     */
    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * Requests the RECORD_AUDIO runtime permission.
     */
    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
