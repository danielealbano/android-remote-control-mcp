package com.danielealbano.androidremotecontrolmcp.services.shell.approval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ElevationApprovalReceiver : BroadcastReceiver() {
    @Inject
    lateinit var coordinator: ElevationApprovalCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        ElevationApprovalIntents.resolve(intent, coordinator)
    }
}
