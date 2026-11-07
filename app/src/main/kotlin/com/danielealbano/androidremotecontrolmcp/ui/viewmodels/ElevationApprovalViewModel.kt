package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.PendingElevation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ElevationApprovalViewModel
    @Inject
    constructor(
        private val coordinator: ElevationApprovalCoordinator,
    ) : ViewModel() {
        val pending: StateFlow<List<PendingElevation>> = coordinator.pending

        fun approve(id: Int) = coordinator.resolve(id, true)

        fun deny(id: Int) = coordinator.resolve(id, false)
    }
