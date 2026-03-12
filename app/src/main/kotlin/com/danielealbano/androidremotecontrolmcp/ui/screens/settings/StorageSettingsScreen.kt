@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val storageLocations by viewModel.storageLocations.collectAsStateWithLifecycle()
    val fileSizeLimitInput by viewModel.fileSizeLimitInput.collectAsStateWithLifecycle()
    val fileSizeLimitError by viewModel.fileSizeLimitError.collectAsStateWithLifecycle()
    val downloadTimeoutInput by viewModel.downloadTimeoutInput.collectAsStateWithLifecycle()
    val downloadTimeoutError by viewModel.downloadTimeoutError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.storageError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogDescription by remember { mutableStateOf("") }
    var addDialogSelectedUri by remember { mutableStateOf<Uri?>(null) }
    var addDialogSelectedName by remember { mutableStateOf<String?>(null) }
    var addDialogDuplicateError by remember { mutableStateOf(false) }
    var addDialogDuplicateChecking by remember { mutableStateOf(false) }

    var showEditDialog by remember { mutableStateOf(false) }
    var editDialogLocation by remember { mutableStateOf<StorageLocation?>(null) }
    var editDialogDescription by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteDialogLocation by remember { mutableStateOf<StorageLocation?>(null) }

    val scope = rememberCoroutineScope()

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { _ -> viewModel.refreshStorageLocations() }

    val documentTreeLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                addDialogSelectedUri = uri
                val docFile = DocumentFile.fromTreeUri(context, uri)
                addDialogSelectedName = docFile?.name ?: uri.lastPathSegment ?: "Unknown"
                addDialogDuplicateChecking = true
                addDialogDuplicateError = false
                scope.launch {
                    addDialogDuplicateError = viewModel.isDuplicateTreeUri(uri)
                    addDialogDuplicateChecking = false
                }
            }
        }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                windowInsets = WindowInsets(0),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                val builtinLocations = storageLocations.filter { it.isBuiltin }
                val userLocations = storageLocations.filter { !it.isBuiltin }

                // ── Built-in Locations Section ──────────────────────────────
                Text(
                    text = stringResource(R.string.storage_builtin_locations_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.storage_builtin_locations_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                builtinLocations.forEach { location ->
                    val builtin = BuiltinStorageLocation.fromLocationId(location.id)
                    val hasAllFiles =
                        builtin?.readMediaPermission?.let {
                            ContextCompat.checkSelfPermission(context, it) ==
                                PackageManager.PERMISSION_GRANTED
                        } ?: false
                    BuiltinStorageLocationRow(
                        location = location,
                        hasAllFilesPermission = hasAllFiles,
                        readMediaPermission = builtin?.readMediaPermission,
                        onAllowWriteChange = { enabled ->
                            viewModel.updateLocationAllowWrite(location.id, enabled)
                        },
                        onAllowDeleteChange = { enabled ->
                            viewModel.updateLocationAllowDelete(location.id, enabled)
                        },
                        onRequestPermission = { permission ->
                            permissionLauncher.launch(permission)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ── User Locations Section ──────────────────────────────────
                Text(
                    text = stringResource(R.string.storage_user_locations_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.storage_locations_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(onClick = {
                    addDialogDescription = ""
                    addDialogSelectedUri = null
                    addDialogSelectedName = null
                    addDialogDuplicateError = false
                    addDialogDuplicateChecking = false
                    showAddDialog = true
                }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.storage_location_add_button))
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (userLocations.isEmpty()) {
                    Text(
                        text = stringResource(R.string.storage_location_no_locations),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    userLocations.forEach { location ->
                        StorageLocationRow(
                            location = location,
                            onEdit = {
                                editDialogLocation = location
                                editDialogDescription = location.description
                                showEditDialog = true
                            },
                            onDelete = {
                                deleteDialogLocation = location
                                showDeleteDialog = true
                            },
                            onAllowWriteChange = { enabled ->
                                viewModel.updateLocationAllowWrite(location.id, enabled)
                            },
                            onAllowDeleteChange = { enabled ->
                                viewModel.updateLocationAllowDelete(location.id, enabled)
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = fileSizeLimitInput,
                    onValueChange = viewModel::updateFileSizeLimit,
                    label = { Text(stringResource(R.string.storage_file_size_limit_label)) },
                    isError = fileSizeLimitError != null,
                    supportingText = fileSizeLimitError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = downloadTimeoutInput,
                    onValueChange = viewModel::updateDownloadTimeout,
                    label = { Text(stringResource(R.string.storage_download_timeout_label)) },
                    isError = downloadTimeoutError != null,
                    supportingText = downloadTimeoutError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.storage_allow_http_downloads_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.storage_allow_http_downloads_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = serverConfig.allowHttpDownloads,
                        onCheckedChange = viewModel::updateAllowHttpDownloads,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.storage_allow_unverified_https_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.storage_allow_unverified_https_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = serverConfig.allowUnverifiedHttpsCerts,
                        onCheckedChange = viewModel::updateAllowUnverifiedHttpsCerts,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
                            text =
                                stringResource(
                                    R.string.storage_location_add_dialog_selected,
                                    addDialogSelectedName!!,
                                ),
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
                    enabled =
                        addDialogSelectedUri != null &&
                            !addDialogDuplicateError &&
                            !addDialogDuplicateChecking,
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

@Suppress("LongMethod")
@Composable
private fun StorageLocationRow(
    location: StorageLocation,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAllowWriteChange: (Boolean) -> Unit,
    onAllowDeleteChange: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = location.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = location.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (location.description.isNotEmpty()) {
                    Text(
                        text = location.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.storage_location_edit_dialog_title),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.storage_location_delete_dialog_title),
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier =
                    Modifier.toggleable(
                        value = location.allowWrite,
                        role = Role.Switch,
                        onValueChange = onAllowWriteChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_location_allow_write_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = location.allowWrite,
                    onCheckedChange = null,
                )
            }
            Row(
                modifier =
                    Modifier.toggleable(
                        value = location.allowDelete,
                        role = Role.Switch,
                        onValueChange = onAllowDeleteChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_location_allow_delete_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = location.allowDelete,
                    onCheckedChange = null,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun BuiltinStorageLocationRow(
    location: StorageLocation,
    hasAllFilesPermission: Boolean,
    readMediaPermission: String?,
    onAllowWriteChange: (Boolean) -> Unit,
    onAllowDeleteChange: (Boolean) -> Unit,
    onRequestPermission: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Text(
            text = location.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = location.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier =
                    Modifier.toggleable(
                        value = location.allowWrite,
                        role = Role.Switch,
                        onValueChange = onAllowWriteChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_location_allow_write_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = location.allowWrite,
                    onCheckedChange = null,
                )
            }
            Row(
                modifier =
                    Modifier.toggleable(
                        value = location.allowDelete,
                        role = Role.Switch,
                        onValueChange = onAllowDeleteChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_location_allow_delete_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = location.allowDelete,
                    onCheckedChange = null,
                )
            }
        }
        if (readMediaPermission != null) {
            OutlinedButton(
                onClick = { onRequestPermission(readMediaPermission) },
                enabled = !hasAllFilesPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (hasAllFilesPermission) {
                            R.string.storage_builtin_all_files_granted
                        } else {
                            R.string.storage_builtin_grant_all_files
                        },
                    ),
                )
            }
        }
    }
}
