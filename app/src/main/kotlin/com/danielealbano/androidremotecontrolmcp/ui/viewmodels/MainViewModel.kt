@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _serverConfig = MutableStateFlow(ServerConfig())
        val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

        private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        private val _portInput = MutableStateFlow("")
        val portInput: StateFlow<String> = _portInput.asStateFlow()

        private val _portError = MutableStateFlow<String?>(null)
        val portError: StateFlow<String?> = _portError.asStateFlow()

        private val _hostnameInput = MutableStateFlow("")
        val hostnameInput: StateFlow<String> = _hostnameInput.asStateFlow()

        private val _hostnameError = MutableStateFlow<String?>(null)
        val hostnameError: StateFlow<String?> = _hostnameError.asStateFlow()

        private val _isAccessibilityEnabled = MutableStateFlow(false)
        val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

        private val _serverLogs = MutableStateFlow<List<ServerLogEntry>>(emptyList())
        val serverLogs: StateFlow<List<ServerLogEntry>> = _serverLogs.asStateFlow()

        init {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.serverConfig.collect { config ->
                    _serverConfig.value = config
                    _portInput.value = config.port.toString()
                    _hostnameInput.value = config.certificateHostname
                }
            }
        }

        @Suppress("ReturnCount")
        fun updatePort(portString: String) {
            _portInput.value = portString

            if (portString.isBlank()) {
                _portError.value = "Port is required"
                return
            }

            val port = portString.toIntOrNull()
            if (port == null) {
                _portError.value = "Port must be a number"
                return
            }

            if (port < MIN_PORT || port > MAX_PORT) {
                _portError.value = "Port must be between $MIN_PORT and $MAX_PORT"
                return
            }

            _portError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updatePort(port)
            }
        }

        fun updateBindingAddress(address: BindingAddress) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateBindingAddress(address)
            }
        }

        fun generateNewBearerToken() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.generateNewBearerToken()
            }
        }

        fun updateAutoStartOnBoot(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateAutoStartOnBoot(enabled)
            }
        }

        fun updateHttpsEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateHttpsEnabled(enabled)
            }
        }

        fun updateCertificateSource(source: CertificateSource) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateCertificateSource(source)
            }
        }

        fun updateCertificateHostname(hostname: String) {
            _hostnameInput.value = hostname

            if (hostname.isBlank()) {
                _hostnameError.value = "Hostname is required"
                return
            }

            if (!HOSTNAME_PATTERN.matches(hostname)) {
                _hostnameError.value = "Invalid hostname format"
                return
            }

            _hostnameError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateCertificateHostname(hostname)
            }
        }

        fun startServer() {
            Log.i(TAG, "startServer called (stub, will be wired in Plan 6)")
        }

        fun stopServer() {
            Log.i(TAG, "stopServer called (stub, will be wired in Plan 6)")
        }

        fun copyToClipboard(
            context: Context,
            text: String,
        ) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(CLIPBOARD_LABEL, text)
            clipboard.setPrimaryClip(clip)
        }

        fun refreshPermissionStatus(context: Context) {
            _isAccessibilityEnabled.value =
                PermissionUtils.isAccessibilityServiceEnabled(
                    context,
                    McpAccessibilityService::class.java,
                )
        }

        fun addServerLogEntry(entry: ServerLogEntry) {
            val currentLogs = _serverLogs.value.toMutableList()
            currentLogs.add(entry)
            if (currentLogs.size > MAX_LOG_ENTRIES) {
                _serverLogs.value = currentLogs.drop(currentLogs.size - MAX_LOG_ENTRIES)
            } else {
                _serverLogs.value = currentLogs
            }
        }

        companion object {
            private const val TAG = "MCP:MainViewModel"
            private const val MIN_PORT = 1
            private const val MAX_PORT = 65535
            private const val MAX_LOG_ENTRIES = 100
            private const val CLIPBOARD_LABEL = "MCP Remote Control"
            private val HOSTNAME_PATTERN =
                Regex(
                    "^[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)*$",
                )
        }
    }
