package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The tunnel-start certificate renewal as the Server and Settings screens show it (with Retry; Cancel is a server
 * stop). The state lives in [ArcTunnelRenewals], so every screen may hold its own instance.
 */
@HiltViewModel
class ArcTunnelRenewalViewModel
    @Inject
    constructor(
        private val renewals: ArcTunnelRenewals,
    ) : ViewModel() {
        val renewal: StateFlow<ArcTunnelRenewalState> = renewals.state

        fun retry() {
            renewals.retry()
        }
    }
