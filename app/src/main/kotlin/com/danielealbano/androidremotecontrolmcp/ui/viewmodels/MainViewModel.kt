@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
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
        private val tunnelManager: TunnelManager,
        private val storageLocationProvider: StorageLocationProvider,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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

        private val _isNotificationPermissionGranted = MutableStateFlow(false)
        val isNotificationPermissionGranted: StateFlow<Boolean> = _isNotificationPermissionGranted.asStateFlow()

        private val _tunnelStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

        private val _ngrokAuthtokenInput = MutableStateFlow("")
        val ngrokAuthtokenInput: StateFlow<String> = _ngrokAuthtokenInput.asStateFlow()

        private val _ngrokDomainInput = MutableStateFlow("")
        val ngrokDomainInput: StateFlow<String> = _ngrokDomainInput.asStateFlow()

        private val _storageLocations = MutableStateFlow<List<StorageLocation>>(emptyList())
        val storageLocations: StateFlow<List<StorageLocation>> = _storageLocations.asStateFlow()

        private val _fileSizeLimitInput = MutableStateFlow("")
        val fileSizeLimitInput: StateFlow<String> = _fileSizeLimitInput.asStateFlow()

        private val _fileSizeLimitError = MutableStateFlow<String?>(null)
        val fileSizeLimitError: StateFlow<String?> = _fileSizeLimitError.asStateFlow()

        private val _downloadTimeoutInput = MutableStateFlow("")
        val downloadTimeoutInput: StateFlow<String> = _downloadTimeoutInput.asStateFlow()

        private val _downloadTimeoutError = MutableStateFlow<String?>(null)
        val downloadTimeoutError: StateFlow<String?> = _downloadTimeoutError.asStateFlow()

        private val _pendingAuthorizationLocationId = MutableStateFlow<String?>(null)
        val pendingAuthorizationLocationId: StateFlow<String?> = _pendingAuthorizationLocationId.asStateFlow()

        init {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.serverConfig.collect { config ->
                    _serverConfig.value = config
                    _portInput.value = config.port.toString()
                    _hostnameInput.value = config.certificateHostname
                    _ngrokAuthtokenInput.value = config.ngrokAuthtoken
                    _ngrokDomainInput.value = config.ngrokDomain
                    _fileSizeLimitInput.value = config.fileSizeLimitMb.toString()
                    _downloadTimeoutInput.value = config.downloadTimeoutSeconds.toString()
                }
            }

            // Collect server status from McpServerService's companion-level StateFlow.
            // This replaces the deprecated LocalBroadcastManager approach with a
            // Kotlin-idiomatic StateFlow collection pattern.
            viewModelScope.launch {
                McpServerService.serverStatus.collect { status ->
                    _serverStatus.value = status
                }
            }

            viewModelScope.launch {
                tunnelManager.tunnelStatus.collect { status ->
                    _tunnelStatus.value = status
                }
            }

            // Collect server log events emitted by McpServerService
            viewModelScope.launch {
                McpServerService.serverLogEvents.collect { entry ->
                    addServerLogEntry(entry)
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

        fun startServer(context: Context) {
            Log.i(TAG, "Starting MCP server via McpServerService")
            _serverStatus.value = ServerStatus.Starting
            val intent =
                Intent(context, McpServerService::class.java).apply {
                    action = McpServerService.ACTION_START
                }
            context.startForegroundService(intent)
        }

        fun stopServer(context: Context) {
            Log.i(TAG, "Stopping MCP server via McpServerService")
            _serverStatus.value = ServerStatus.Stopping
            val intent =
                Intent(context, McpServerService::class.java).apply {
                    action = McpServerService.ACTION_STOP
                }
            context.startForegroundService(intent)
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
            _isNotificationPermissionGranted.value =
                PermissionUtils.isNotificationPermissionGranted(context)
            refreshStorageLocations()
        }

        fun updateTunnelEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateTunnelEnabled(enabled)
            }
        }

        fun updateTunnelProvider(provider: TunnelProviderType) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateTunnelProvider(provider)
            }
        }

        fun updateNgrokAuthtoken(authtoken: String) {
            _ngrokAuthtokenInput.value = authtoken
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateNgrokAuthtoken(authtoken)
            }
        }

        fun updateNgrokDomain(domain: String) {
            _ngrokDomainInput.value = domain
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateNgrokDomain(domain)
            }
        }

        fun refreshStorageLocations() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    _storageLocations.value = storageLocationProvider.getAvailableLocations()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh storage locations", e)
                }
            }
        }

        fun requestLocationAuthorization(locationId: String) {
            _pendingAuthorizationLocationId.value = locationId
        }

        fun onLocationAuthorized(treeUri: Uri) {
            val locationId = _pendingAuthorizationLocationId.value
            _pendingAuthorizationLocationId.value = null
            if (locationId == null) {
                Log.w(TAG, "onLocationAuthorized called but no pending location ID")
                return
            }
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.authorizeLocation(locationId, treeUri)
                    refreshStorageLocations()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to authorize location $locationId", e)
                }
            }
        }

        fun onLocationAuthorizationCancelled() {
            _pendingAuthorizationLocationId.value = null
        }

        fun deauthorizeLocation(locationId: String) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.deauthorizeLocation(locationId)
                    refreshStorageLocations()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deauthorize location $locationId", e)
                }
            }
        }

        @Suppress("ReturnCount")
        fun updateFileSizeLimit(limitString: String) {
            _fileSizeLimitInput.value = limitString

            if (limitString.isBlank()) {
                _fileSizeLimitError.value = "File size limit is required"
                return
            }

            val limit = limitString.toIntOrNull()
            if (limit == null) {
                _fileSizeLimitError.value = "Must be a number"
                return
            }

            val result = settingsRepository.validateFileSizeLimit(limit)
            if (result.isFailure) {
                _fileSizeLimitError.value = result.exceptionOrNull()?.message
                return
            }

            _fileSizeLimitError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateFileSizeLimit(limit)
            }
        }

        @Suppress("ReturnCount")
        fun updateDownloadTimeout(timeoutString: String) {
            _downloadTimeoutInput.value = timeoutString

            if (timeoutString.isBlank()) {
                _downloadTimeoutError.value = "Download timeout is required"
                return
            }

            val timeout = timeoutString.toIntOrNull()
            if (timeout == null) {
                _downloadTimeoutError.value = "Must be a number"
                return
            }

            val result = settingsRepository.validateDownloadTimeout(timeout)
            if (result.isFailure) {
                _downloadTimeoutError.value = result.exceptionOrNull()?.message
                return
            }

            _downloadTimeoutError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateDownloadTimeout(timeout)
            }
        }

        fun updateAllowHttpDownloads(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateAllowHttpDownloads(enabled)
            }
        }

        fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateAllowUnverifiedHttpsCerts(enabled)
            }
        }

        fun getInitialPickerUri(locationId: String): Uri? {
            val location = _storageLocations.value.find { it.id == locationId } ?: return null
            return try {
                DocumentsContract.buildDocumentUri(location.authority, location.rootDocumentId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build picker URI for $locationId", e)
                null
            }
        }

        fun shareText(
            context: Context,
            text: String,
        ) {
            val sendIntent =
                Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                }
            val shareIntent = Intent.createChooser(sendIntent, null)
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
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
