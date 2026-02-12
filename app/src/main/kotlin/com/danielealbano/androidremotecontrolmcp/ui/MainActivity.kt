package com.danielealbano.androidremotecontrolmcp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel: MainViewModel by viewModels()

        mediaProjectionLauncher =
            registerForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    viewModel.setMediaProjectionResult(result.resultCode, result.data!!)
                }
            }

        setContent {
            AndroidRemoteControlMcpTheme {
                HomeScreen(
                    onRequestMediaProjectionPermission = ::requestMediaProjectionPermission,
                )
            }
        }
    }

    /**
     * Launches the system MediaProjection permission dialog.
     *
     * Called from the PermissionsSection "Grant" button for screen capture.
     */
    private fun requestMediaProjectionPermission() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        @Suppress("UnusedPrivateProperty")
        private const val TAG = "MCP:MainActivity"
    }
}
