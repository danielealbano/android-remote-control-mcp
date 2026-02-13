package com.danielealbano.androidremotecontrolmcp.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
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
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaProjectionLauncher =
            registerForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                viewModel.setMediaProjectionResult(result.resultCode, result.data)
            }

        setContent {
            AndroidRemoteControlMcpTheme {
                HomeScreen(
                    onRequestMediaProjectionPermission = ::requestMediaProjectionPermission,
                )
            }
        }

        // Auto-trigger MediaProjection permission request if launched with the extra.
        // This is used by E2E tests to programmatically request the permission.
        handleMediaProjectionExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent called")
        setIntent(intent)
        handleMediaProjectionExtra(intent)
    }

    private fun handleMediaProjectionExtra(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_REQUEST_MEDIA_PROJECTION, false)) {
            Log.i(TAG, "Received request_media_projection=true, requesting permission")
            requestMediaProjectionPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus(this)
    }
    // NOTE: No onPause() needed for broadcast receiver since we use StateFlow
    // for server status, which is collected in MainViewModel via viewModelScope.

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
        private const val TAG = "MCP:MainActivity"

        /**
         * Intent extra to auto-trigger MediaProjection permission request on launch.
         * Used by E2E tests to programmatically request the permission.
         */
        const val EXTRA_REQUEST_MEDIA_PROJECTION = "request_media_projection"
    }
}
