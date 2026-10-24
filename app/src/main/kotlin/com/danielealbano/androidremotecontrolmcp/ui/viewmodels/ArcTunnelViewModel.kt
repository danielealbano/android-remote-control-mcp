package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlert
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlerts
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHosts
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The saved public URL of the free E2E tunnel: unknown until the store was read, then absent or present. */
sealed class ArcTunnelUrlState {
    data object Loading : ArcTunnelUrlState()

    data object NotEnrolled : ArcTunnelUrlState()

    data class Enrolled(
        val url: String,
    ) : ArcTunnelUrlState()
}

/**
 * UI state and actions of the free E2E tunnel provider (enrollment, URL, CT toggle, CT alert). ONE instance is
 * created at `MainScreen` level and passed to the Server and Settings screens (shared enrollment request).
 */
@HiltViewModel
class ArcTunnelViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val enrollment: ArcTunnelEnrollment,
        private val renewals: ArcTunnelRenewals,
        private val ctAlerts: ArcTunnelCtAlerts,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _baseAddressInput = MutableStateFlow(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)
        val baseAddressInput: StateFlow<String> = _baseAddressInput.asStateFlow()

        private val _baseAddressValid = MutableStateFlow(true)
        val baseAddressValid: StateFlow<Boolean> = _baseAddressValid.asStateFlow()

        private val _url = MutableStateFlow<ArcTunnelUrlState>(ArcTunnelUrlState.Loading)
        val url: StateFlow<ArcTunnelUrlState> = _url.asStateFlow()

        /** True from "Remote Access turned on with this provider" until the dialog was answered. */
        private val _enrollmentRequested = MutableStateFlow(false)
        val enrollmentRequested: StateFlow<Boolean> = _enrollmentRequested.asStateFlow()

        val enrollmentState: StateFlow<ArcTunnelEnrollmentState> = enrollment.state

        val ctAlert: StateFlow<ArcTunnelCtAlert?> = ctAlerts.alert

        @Volatile
        private var env: ArcTunnelEnv = ArcTunnelEnv(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)

        /** The persisted base address; null until the first configuration was read. */
        @Volatile
        private var persistedBaseAddress: String? = null

        init {
            viewModelScope.launch(ioDispatcher) {
                var previousSelected = false
                var alertRestored = false
                settingsRepository.serverConfig.collect { config ->
                    env = ArcTunnelEnv.from(config)
                    // The input mirrors the PERSISTED value only when that value changes, so a config emission
                    // (e.g. the CT switch) never overwrites what the user is typing.
                    if (config.arcTunnelBaseAddress != persistedBaseAddress) {
                        persistedBaseAddress = config.arcTunnelBaseAddress
                        _baseAddressInput.value = config.arcTunnelBaseAddress
                        _baseAddressValid.value = true
                    }
                    val selected = config.tunnelEnabled && config.tunnelProvider == TunnelProviderType.ARC_TUNNEL
                    // The request survives the provider-only emission of enableFreeTunnel (not selected → not
                    // selected) and is dropped only when the selection is LOST (Cancel, other provider).
                    if (previousSelected && !selected) _enrollmentRequested.value = false
                    previousSelected = selected
                    refreshUrl()
                    if (!alertRestored) {
                        alertRestored = true
                        if (ctAlerts.alert.value == null) enrollment.savedCtAlert(env)?.let { ctAlerts.restore(it) }
                    }
                }
            }
            viewModelScope.launch(ioDispatcher) {
                var previous: ArcTunnelEnrollmentState = ArcTunnelEnrollmentState.Idle
                enrollment.state.collect { state ->
                    // Leaving Enrolling, or a failure cleared by a later renewal, may have saved a URL.
                    if (previous !is ArcTunnelEnrollmentState.Idle) refreshUrl()
                    previous = state
                }
            }
            viewModelScope.launch(ioDispatcher) {
                var previous: ArcTunnelRenewalState = ArcTunnelRenewalState.Idle
                renewals.state.collect { state ->
                    // A tunnel-start renewal that ended may have saved the certificate (and so the URL).
                    if (previous is ArcTunnelRenewalState.Renewing) refreshUrl()
                    previous = state
                }
            }
        }

        /** Validates live; nothing is persisted until [commitBaseAddress] (so no environment flips while typing). */
        fun updateBaseAddressInput(value: String) {
            _baseAddressInput.value = value
            _baseAddressValid.value = ArcTunnelHosts.isValidBaseAddress(value)
        }

        /** Persists the typed base address when valid and changed (focus loss); nothing before the first config. */
        fun commitBaseAddress() {
            val persisted = persistedBaseAddress ?: return
            val value = _baseAddressInput.value
            if (!ArcTunnelHosts.isValidBaseAddress(value) || value == persisted) return
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateArcTunnelBaseAddress(value) }
        }

        fun updateCtCheckEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateArcTunnelCtCheckEnabled(enabled) }
        }

        /** Home button: selects the provider, turns Remote Access on, then (both writes done) requests the dialog. */
        fun enableFreeTunnel() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateTunnelProvider(TunnelProviderType.ARC_TUNNEL)
                settingsRepository.updateTunnelEnabled(true)
                _enrollmentRequested.value = true
            }
        }

        /** The settings toggle turned Remote Access on while this provider was selected. */
        fun requestEnrollment() {
            _enrollmentRequested.value = true
        }

        /**
         * Dialog "Continue", "Set up tunnel" and "Retry": enrolls against the TYPED base address (persisted
         * first when it changed — a button press does not take the focus from the text field).
         */
        fun enroll() {
            _enrollmentRequested.value = false
            val typed = _baseAddressInput.value
            val target = if (ArcTunnelHosts.isValidBaseAddress(typed)) env.copy(baseAddress = typed) else env
            viewModelScope.launch(ioDispatcher) {
                if (target.baseAddress != persistedBaseAddress) {
                    settingsRepository.updateArcTunnelBaseAddress(target.baseAddress)
                }
                enrollment.requestEnroll(target)
            }
        }

        /** Dialog "Cancel" / outside tap: Remote Access goes back off. */
        fun cancelEnrollment() {
            _enrollmentRequested.value = false
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTunnelEnabled(false) }
        }

        /** Refresh icon, confirmed: new name + new keys; a pending CT alert belongs to the old name and goes first. */
        fun rotateName() {
            viewModelScope.launch(ioDispatcher) {
                enrollment.dismissCtAlert(env)
                ctAlerts.dismiss()
                enrollment.requestRotate(env)
            }
        }

        /** The persisted alert is cleared BEFORE the banner hides, so a config emission cannot restore it. */
        fun dismissCtAlert() {
            viewModelScope.launch(ioDispatcher) {
                enrollment.dismissCtAlert(env)
                ctAlerts.dismiss()
            }
        }

        /** Re-reads the saved URL; an enrolled URL ends a pending enrollment request. */
        private fun refreshUrl() {
            val url = enrollment.savedUrl(env)
            _url.value = url?.let { ArcTunnelUrlState.Enrolled(it) } ?: ArcTunnelUrlState.NotEnrolled
            if (url != null) _enrollmentRequested.value = false
        }
    }
