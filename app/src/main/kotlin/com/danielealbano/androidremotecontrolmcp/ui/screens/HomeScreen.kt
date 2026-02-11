@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.provider.Settings
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
import com.danielealbano.androidremotecontrolmcp.ui.components.ConfigurationSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCard
import com.danielealbano.androidremotecontrolmcp.ui.components.PermissionsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val portError by viewModel.portError.collectAsStateWithLifecycle()
    val hostnameInput by viewModel.hostnameInput.collectAsStateWithLifecycle()
    val hostnameError by viewModel.hostnameError.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val serverLogs by viewModel.serverLogs.collectAsStateWithLifecycle()

    val isServerRunning =
        serverStatus is ServerStatus.Running ||
            serverStatus is ServerStatus.Starting

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
                onStartClick = viewModel::startServer,
                onStopClick = viewModel::stopServer,
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

            PermissionsSection(
                isAccessibilityEnabled = isAccessibilityEnabled,
                onOpenAccessibilitySettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                },
                onRequestNotificationPermission = {
                    // Notification permission handling will be refined in Plan 6
                },
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
                onCopyAll = { connectionString ->
                    viewModel.copyToClipboard(context, connectionString)
                },
            )
        }
    }
}
