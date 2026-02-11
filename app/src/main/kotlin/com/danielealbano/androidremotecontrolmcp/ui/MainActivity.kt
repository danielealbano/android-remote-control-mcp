package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.danielealbano.androidremotecontrolmcp.ui.screens.HomeScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidRemoteControlMcpTheme {
                HomeScreen()
            }
        }
    }

    companion object {
        @Suppress("UnusedPrivateProperty")
        private const val TAG = "MCP:MainActivity"
    }
}
