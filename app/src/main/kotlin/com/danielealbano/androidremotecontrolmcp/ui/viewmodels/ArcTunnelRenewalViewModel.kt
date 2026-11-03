package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The tunnel-start certificate renewals as the screens show them (with Retry; Cancel is a server stop). The state
 * lives in [ArcTunnelRenewals], so every screen may hold its own instance; only the one showing the modal uses
 * [modalVisible].
 */
@HiltViewModel
class ArcTunnelRenewalViewModel
    @Inject
    constructor(
        private val renewals: ArcTunnelRenewals,
    ) : ViewModel() {
        val renewal: StateFlow<ArcTunnelRenewalState> = renewals.state
        private val modalHidden = MutableStateFlow(false)

        /** While a renewal runs, until the user hides the modal for that renewal. */
        val modalVisible: StateFlow<Boolean> =
            combine(renewals.state, modalHidden) { state, hidden ->
                state is ArcTunnelRenewalState.Renewing && !hidden
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        init {
            viewModelScope.launch {
                renewals.state.collect { if (it !is ArcTunnelRenewalState.Renewing) modalHidden.value = false }
            }
        }

        fun hideModal() {
            modalHidden.value = true
        }

        fun retry() {
            renewals.retry()
        }
    }
