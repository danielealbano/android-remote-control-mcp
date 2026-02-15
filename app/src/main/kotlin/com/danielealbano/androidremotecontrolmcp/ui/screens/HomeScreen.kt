@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.ui.components.ConfigurationSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCard
import com.danielealbano.androidremotecontrolmcp.ui.components.PermissionsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.RemoteAccessSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.components.StorageLocationsSection
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRequestNotificationPermission: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val portError by viewModel.portError.collectAsStateWithLifecycle()
    val hostnameInput by viewModel.hostnameInput.collectAsStateWithLifecycle()
    val hostnameError by viewModel.hostnameError.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isNotificationPermissionGranted by viewModel.isNotificationPermissionGranted.collectAsStateWithLifecycle()
    val serverLogs by viewModel.serverLogs.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val ngrokAuthtokenInput by viewModel.ngrokAuthtokenInput.collectAsStateWithLifecycle()
    val ngrokDomainInput by viewModel.ngrokDomainInput.collectAsStateWithLifecycle()
    val storageLocations by viewModel.storageLocations.collectAsStateWithLifecycle()
    val fileSizeLimitInput by viewModel.fileSizeLimitInput.collectAsStateWithLifecycle()
    val fileSizeLimitError by viewModel.fileSizeLimitError.collectAsStateWithLifecycle()
    val downloadTimeoutInput by viewModel.downloadTimeoutInput.collectAsStateWithLifecycle()
    val downloadTimeoutError by viewModel.downloadTimeoutError.collectAsStateWithLifecycle()

    val isServerRunning =
        serverStatus is ServerStatus.Running ||
            serverStatus is ServerStatus.Starting

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Permission is taken inside StorageLocationProviderImpl.authorizeLocation()
            // — do NOT call takePersistableUriPermission here to avoid a double call.
            viewModel.onLocationAuthorized(uri)
        } else {
            viewModel.onLocationAuthorizationCancelled()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPermissionStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.app_bar_title))
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerStatusCard(
                status = serverStatus,
                onStartClick = { viewModel.startServer(context) },
                onStopClick = { viewModel.stopServer(context) },
            )

            ConfigurationSection(
                bindingAddress = serverConfig.bindingAddress,
                portInput = portInput,
                portError = portError,
                bearerToken = serverConfig.bearerToken,
                autoStartEnabled = serverConfig.autoStartOnBoot,
                httpsEnabled = serverConfig.httpsEnabled,
                certificateSource = serverConfig.certificateSource,
                hostnameInput = hostnameInput,
                hostnameError = hostnameError,
                isServerRunning = isServerRunning,
                onBindingAddressChange = viewModel::updateBindingAddress,
                onPortChange = viewModel::updatePort,
                onRegenerateToken = viewModel::generateNewBearerToken,
                onCopyToken = { viewModel.copyToClipboard(context, serverConfig.bearerToken) },
                onAutoStartChange = viewModel::updateAutoStartOnBoot,
                onHttpsEnabledChange = viewModel::updateHttpsEnabled,
                onCertificateSourceChange = viewModel::updateCertificateSource,
                onHostnameChange = viewModel::updateCertificateHostname,
            )

            StorageLocationsSection(
                storageLocations = storageLocations,
                fileSizeLimitInput = fileSizeLimitInput,
                fileSizeLimitError = fileSizeLimitError,
                downloadTimeoutInput = downloadTimeoutInput,
                downloadTimeoutError = downloadTimeoutError,
                allowHttpDownloads = serverConfig.allowHttpDownloads,
                allowUnverifiedHttpsCerts = serverConfig.allowUnverifiedHttpsCerts,
                isServerRunning = isServerRunning,
                onToggleLocation = { location ->
                    if (location.isAuthorized) {
                        viewModel.deauthorizeLocation(location.id)
                    } else {
                        viewModel.requestLocationAuthorization(location.id)
                        val initialUri = viewModel.getInitialPickerUri(location.id)
                        documentTreeLauncher.launch(initialUri)
                    }
                },
                onFileSizeLimitChange = viewModel::updateFileSizeLimit,
                onDownloadTimeoutChange = viewModel::updateDownloadTimeout,
                onAllowHttpDownloadsChange = viewModel::updateAllowHttpDownloads,
                onAllowUnverifiedHttpsCertsChange = viewModel::updateAllowUnverifiedHttpsCerts,
            )

            RemoteAccessSection(
                tunnelEnabled = serverConfig.tunnelEnabled,
                tunnelProvider = serverConfig.tunnelProvider,
                ngrokAuthtoken = ngrokAuthtokenInput,
                ngrokDomain = ngrokDomainInput,
                tunnelStatus = tunnelStatus,
                isServerRunning = isServerRunning,
                onTunnelEnabledChange = viewModel::updateTunnelEnabled,
                onTunnelProviderChange = viewModel::updateTunnelProvider,
                onNgrokAuthtokenChange = viewModel::updateNgrokAuthtoken,
                onNgrokDomainChange = viewModel::updateNgrokDomain,
            )

            PermissionsSection(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isNotificationPermissionGranted = isNotificationPermissionGranted,
                onOpenAccessibilitySettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                },
                onRequestNotificationPermission = onRequestNotificationPermission,
            )

            ServerLogsSection(
                logs = serverLogs,
            )

            ConnectionInfoCard(
                bindingAddress = serverConfig.bindingAddress,
                ipAddress = NetworkUtils.getDeviceIpAddress(context) ?: "N/A",
                port = serverConfig.port,
                httpsEnabled = serverConfig.httpsEnabled,
                bearerToken = serverConfig.bearerToken,
                tunnelUrl = (tunnelStatus as? TunnelStatus.Connected)?.url,
                onCopyAll = { connectionString ->
                    viewModel.copyToClipboard(context, connectionString)
                },
                onShare = { text -> viewModel.shareText(context, text) },
            )
        }
    }
}
