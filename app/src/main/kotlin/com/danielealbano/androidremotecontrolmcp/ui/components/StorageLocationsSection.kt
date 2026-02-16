@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

@Suppress("LongMethod")
@Composable
fun StorageLocationsSection(
    storageLocations: List<StorageLocation>,
    fileSizeLimitInput: String,
    fileSizeLimitError: String?,
    downloadTimeoutInput: String,
    downloadTimeoutError: String?,
    allowHttpDownloads: Boolean,
    allowUnverifiedHttpsCerts: Boolean,
    isServerRunning: Boolean,
    onAddLocation: () -> Unit,
    onEditDescription: (StorageLocation) -> Unit,
    onDeleteLocation: (StorageLocation) -> Unit,
    onAllowWriteChange: (StorageLocation, Boolean) -> Unit,
    onAllowDeleteChange: (StorageLocation, Boolean) -> Unit,
    onFileSizeLimitChange: (String) -> Unit,
    onDownloadTimeoutChange: (String) -> Unit,
    onAllowHttpDownloadsChange: (Boolean) -> Unit,
    onAllowUnverifiedHttpsCertsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.storage_locations_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = stringResource(R.string.storage_locations_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onAddLocation) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.storage_location_add_button))
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (storageLocations.isEmpty()) {
                Text(
                    text = stringResource(R.string.storage_location_no_locations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                storageLocations.forEach { location ->
                    StorageLocationRow(
                        location = location,
                        onEdit = { onEditDescription(location) },
                        onDelete = { onDeleteLocation(location) },
                        onAllowWriteChange = { enabled -> onAllowWriteChange(location, enabled) },
                        onAllowDeleteChange = { enabled -> onAllowDeleteChange(location, enabled) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = fileSizeLimitInput,
                onValueChange = onFileSizeLimitChange,
                label = { Text(stringResource(R.string.storage_file_size_limit_label)) },
                isError = fileSizeLimitError != null,
                supportingText = fileSizeLimitError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !isServerRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = downloadTimeoutInput,
                onValueChange = onDownloadTimeoutChange,
                label = { Text(stringResource(R.string.storage_download_timeout_label)) },
                isError = downloadTimeoutError != null,
                supportingText = downloadTimeoutError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !isServerRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
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
                    checked = allowHttpDownloads,
                    onCheckedChange = onAllowHttpDownloadsChange,
                    enabled = !isServerRunning,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
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
                    checked = allowUnverifiedHttpsCerts,
                    onCheckedChange = onAllowUnverifiedHttpsCertsChange,
                    enabled = !isServerRunning,
                )
            }
        }
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
            Column(
                modifier = Modifier.weight(1f),
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
                    onCheckedChange = onAllowWriteChange,
                    modifier = Modifier.height(24.dp),
                )
            }
            Row(
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
                    onCheckedChange = onAllowDeleteChange,
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun StorageLocationsSectionPreview() {
    AndroidRemoteControlMcpTheme {
        StorageLocationsSection(
            storageLocations =
                listOf(
                    StorageLocation(
                        id = "com.android.externalstorage/primary",
                        name = "Internal Storage",
                        path = "/",
                        description = "",
                        treeUri = "content://com.android.externalstorage.documents/tree/primary%3A",
                        availableBytes = null,
                        allowWrite = true,
                        allowDelete = false,
                    ),
                    StorageLocation(
                        id = "com.android.providers.downloads.documents/downloads",
                        name = "Downloads",
                        path = "/",
                        description = "Downloaded files",
                        treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                ),
            fileSizeLimitInput = "50",
            fileSizeLimitError = null,
            downloadTimeoutInput = "60",
            downloadTimeoutError = null,
            allowHttpDownloads = false,
            allowUnverifiedHttpsCerts = false,
            isServerRunning = false,
            onAddLocation = {},
            onEditDescription = {},
            onDeleteLocation = {},
            onAllowWriteChange = { _, _ -> },
            onAllowDeleteChange = { _, _ -> },
            onFileSizeLimitChange = {},
            onDownloadTimeoutChange = {},
            onAllowHttpDownloadsChange = {},
            onAllowUnverifiedHttpsCertsChange = {},
        )
    }
}
