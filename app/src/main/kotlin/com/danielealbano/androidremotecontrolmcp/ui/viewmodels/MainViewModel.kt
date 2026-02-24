@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

        private val _isCameraPermissionGranted = MutableStateFlow(false)
        val isCameraPermissionGranted: StateFlow<Boolean> = _isCameraPermissionGranted.asStateFlow()

        private val _isMicrophonePermissionGranted = MutableStateFlow(false)
        val isMicrophonePermissionGranted: StateFlow<Boolean> = _isMicrophonePermissionGranted.asStateFlow()

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

        private val _deviceSlugInput = MutableStateFlow("")
        val deviceSlugInput: StateFlow<String> = _deviceSlugInput.asStateFlow()

        private val _deviceSlugError = MutableStateFlow<String?>(null)
        val deviceSlugError: StateFlow<String?> = _deviceSlugError.asStateFlow()

        private val _storageError = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val storageError: SharedFlow<String> = _storageError.asSharedFlow()

        init {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.serverConfig.collect { config ->
                    _serverConfig.value = config
                    // Always sync text input fields from the authoritative config so that
                    // external changes (e.g. ADB broadcast) are reflected in the UI.
                    // StateFlow deduplicates equal values, so user-initiated saves that
                    // round-trip through DataStore do not cause extra recompositions.
                    _portInput.value = config.port.toString()
                    _portError.value = null
                    _hostnameInput.value = config.certificateHostname
                    _hostnameError.value = null
                    _ngrokAuthtokenInput.value = config.ngrokAuthtoken
                    _ngrokDomainInput.value = config.ngrokDomain
                    _fileSizeLimitInput.value = config.fileSizeLimitMb.toString()
                    _fileSizeLimitError.value = null
                    _downloadTimeoutInput.value = config.downloadTimeoutSeconds.toString()
                    _downloadTimeoutError.value = null
                    _deviceSlugInput.value = config.deviceSlug
                    _deviceSlugError.value = null
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

            if (port < ServerConfig.MIN_PORT || port > ServerConfig.MAX_PORT) {
                _portError.value = "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}"
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

            val result = settingsRepository.validateCertificateHostname(hostname)
            if (result.isFailure) {
                _hostnameError.value = result.exceptionOrNull()?.message
                return
            }

            _hostnameError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateCertificateHostname(hostname)
            }
        }

        fun startServer(context: Context) {
            Logger.i(TAG, "Starting MCP server via McpServerService")
            _serverStatus.value = ServerStatus.Starting
            val intent =
                Intent(context, McpServerService::class.java).apply {
                    action = McpServerService.ACTION_START
                }
            context.startForegroundService(intent)
        }

        fun stopServer(context: Context) {
            Logger.i(TAG, "Stopping MCP server via McpServerService")
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
            _isCameraPermissionGranted.value =
                PermissionUtils.isCameraPermissionGranted(context)
            _isMicrophonePermissionGranted.value =
                PermissionUtils.isMicrophonePermissionGranted(context)
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

        @Suppress("TooGenericExceptionCaught")
        fun refreshStorageLocations() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    _storageLocations.value = storageLocationProvider.getAllLocations()
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to refresh storage locations", e)
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun addLocation(
            treeUri: Uri,
            description: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.addLocation(treeUri, description)
                    refreshStorageLocations()
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to add storage location", e)
                    _storageError.tryEmit("Failed to add storage location: ${e.message}")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun removeLocation(locationId: String) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.removeLocation(locationId)
                    refreshStorageLocations()
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to remove storage location $locationId", e)
                    _storageError.tryEmit("Failed to remove storage location: ${e.message}")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun updateLocationDescription(
            locationId: String,
            description: String,
        ) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.updateLocationDescription(locationId, description)
                    refreshStorageLocations()
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to update description for $locationId", e)
                    _storageError.tryEmit("Failed to update description: ${e.message}")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun updateLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.updateLocationAllowWrite(locationId, allowWrite)
                    _storageLocations.update { locations ->
                        locations.map { loc ->
                            if (loc.id == locationId) loc.copy(allowWrite = allowWrite) else loc
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to update allowWrite for $locationId", e)
                    refreshStorageLocations()
                    _storageError.tryEmit("Failed to update write permission: ${e.message}")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun updateLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    storageLocationProvider.updateLocationAllowDelete(locationId, allowDelete)
                    _storageLocations.update { locations ->
                        locations.map { loc ->
                            if (loc.id == locationId) loc.copy(allowDelete = allowDelete) else loc
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to update allowDelete for $locationId", e)
                    refreshStorageLocations()
                    _storageError.tryEmit("Failed to update delete permission: ${e.message}")
                }
            }
        }

        suspend fun isDuplicateTreeUri(treeUri: Uri): Boolean = storageLocationProvider.isDuplicateTreeUri(treeUri)

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

        fun updateDeviceSlug(slug: String) {
            _deviceSlugInput.value = slug

            val result = settingsRepository.validateDeviceSlug(slug)
            if (result.isFailure) {
                _deviceSlugError.value = result.exceptionOrNull()?.message
                return
            }

            _deviceSlugError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateDeviceSlug(slug)
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
            _serverLogs.update { currentLogs ->
                val updated = currentLogs + entry
                if (updated.size > MAX_LOG_ENTRIES) {
                    updated.drop(updated.size - MAX_LOG_ENTRIES)
                } else {
                    updated
                }
            }
        }

        companion object {
            private const val TAG = "MCP:MainViewModel"
            private const val MAX_LOG_ENTRIES = 100
            private const val CLIPBOARD_LABEL = "MCP Remote Control"
        }
    }
