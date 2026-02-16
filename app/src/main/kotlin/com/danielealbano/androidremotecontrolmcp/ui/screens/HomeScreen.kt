@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.ui.components.ConfigurationSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCard
import com.danielealbano.androidremotecontrolmcp.ui.components.PermissionsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.RemoteAccessSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.components.StorageLocationsSection
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils
import kotlinx.coroutines.launch

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
    val deviceSlugInput by viewModel.deviceSlugInput.collectAsStateWithLifecycle()
    val deviceSlugError by viewModel.deviceSlugError.collectAsStateWithLifecycle()
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

    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogDescription by remember { mutableStateOf("") }
    var addDialogSelectedUri by remember { mutableStateOf<Uri?>(null) }
    var addDialogSelectedName by remember { mutableStateOf<String?>(null) }
    var addDialogDuplicateError by remember { mutableStateOf(false) }

    var showEditDialog by remember { mutableStateOf(false) }
    var editDialogLocation by remember { mutableStateOf<StorageLocation?>(null) }
    var editDialogDescription by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteDialogLocation by remember { mutableStateOf<StorageLocation?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.storageError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val isServerRunning =
        serverStatus is ServerStatus.Running ||
            serverStatus is ServerStatus.Starting

    val documentTreeLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                addDialogSelectedUri = uri
                val docFile = DocumentFile.fromTreeUri(context, uri)
                addDialogSelectedName = docFile?.name ?: uri.lastPathSegment ?: "Unknown"
                scope.launch {
                    addDialogDuplicateError = viewModel.isDuplicateTreeUri(uri)
                }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                deviceSlugInput = deviceSlugInput,
                deviceSlugError = deviceSlugError,
                bearerToken = serverConfig.bearerToken,
                autoStartEnabled = serverConfig.autoStartOnBoot,
                httpsEnabled = serverConfig.httpsEnabled,
                certificateSource = serverConfig.certificateSource,
                hostnameInput = hostnameInput,
                hostnameError = hostnameError,
                isServerRunning = isServerRunning,
                onBindingAddressChange = viewModel::updateBindingAddress,
                onPortChange = viewModel::updatePort,
                onDeviceSlugChange = viewModel::updateDeviceSlug,
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
                onAddLocation = {
                    addDialogDescription = ""
                    addDialogSelectedUri = null
                    addDialogSelectedName = null
                    addDialogDuplicateError = false
                    showAddDialog = true
                },
                onEditDescription = { location ->
                    editDialogLocation = location
                    editDialogDescription = location.description
                    showEditDialog = true
                },
                onDeleteLocation = { location ->
                    deleteDialogLocation = location
                    showDeleteDialog = true
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

    // Add Storage Location Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.storage_location_add_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = addDialogDescription,
                        onValueChange = { newValue ->
                            if (newValue.length <= StorageLocationProvider.MAX_DESCRIPTION_LENGTH) {
                                addDialogDescription = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.storage_location_add_dialog_description_label)) },
                        placeholder = { Text(stringResource(R.string.storage_location_add_dialog_description_hint)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.storage_location_description_counter,
                                    addDialogDescription.length,
                                    StorageLocationProvider.MAX_DESCRIPTION_LENGTH,
                                ),
                            )
                        },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { documentTreeLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.storage_location_add_dialog_browse))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (addDialogSelectedName != null) {
                        Text(
                            text = stringResource(R.string.storage_location_add_dialog_selected, addDialogSelectedName!!),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.storage_location_add_dialog_no_selection),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (addDialogDuplicateError) {
                        Text(
                            text = stringResource(R.string.storage_location_add_dialog_duplicate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        addDialogSelectedUri?.let { uri ->
                            viewModel.addLocation(uri, addDialogDescription)
                        }
                        showAddDialog = false
                    },
                    enabled = addDialogSelectedUri != null && !addDialogDuplicateError,
                ) {
                    Text(stringResource(R.string.storage_location_add_dialog_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.storage_location_add_dialog_cancel))
                }
            },
        )
    }

    // Edit Description Dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.storage_location_edit_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editDialogDescription,
                        onValueChange = { newValue ->
                            if (newValue.length <= StorageLocationProvider.MAX_DESCRIPTION_LENGTH) {
                                editDialogDescription = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.storage_location_add_dialog_description_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.storage_location_description_counter,
                                    editDialogDescription.length,
                                    StorageLocationProvider.MAX_DESCRIPTION_LENGTH,
                                ),
                            )
                        },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editDialogLocation?.let { location ->
                            viewModel.updateLocationDescription(location.id, editDialogDescription)
                        }
                        showEditDialog = false
                    },
                ) {
                    Text(stringResource(R.string.storage_location_edit_dialog_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.storage_location_add_dialog_cancel))
                }
            },
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.storage_location_delete_dialog_title)) },
            text = {
                deleteDialogLocation?.let { location ->
                    Text(
                        stringResource(R.string.storage_location_delete_dialog_message, location.name),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialogLocation?.let { location ->
                            viewModel.removeLocation(location.id)
                        }
                        showDeleteDialog = false
                    },
                ) {
                    Text(
                        text = stringResource(R.string.storage_location_delete_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.storage_location_delete_dialog_cancel))
                }
            },
        )
    }
}
