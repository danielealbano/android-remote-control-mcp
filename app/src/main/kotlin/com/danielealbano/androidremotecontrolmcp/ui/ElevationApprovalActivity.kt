package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalIntents
import com.danielealbano.androidremotecontrolmcp.ui.screens.ElevationApprovalScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ElevationApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getIntExtra(ElevationApprovalIntents.EXTRA_REQUEST_ID, 0)
        setContent {
            AndroidRemoteControlMcpTheme {
                ElevationApprovalScreen(requestId = requestId, onDone = ::finish)
            }
        }
    }
}
