@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private val AuthorizedColor = Color(0xFF4CAF50)
private val UnauthorizedColor = Color(0xFFF44336)

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
    onToggleLocation: (StorageLocation) -> Unit,
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
                        onToggle = { onToggleLocation(location) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // File Size Limit
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

            // Download Timeout
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

            // Allow HTTP Downloads toggle
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

            // Allow Unverified HTTPS toggle
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

@Composable
private fun StorageLocationRow(
    location: StorageLocation,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                if (location.isAuthorized) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Default.Error
                },
            contentDescription =
                if (location.isAuthorized) {
                    stringResource(R.string.storage_location_authorized)
                } else {
                    stringResource(R.string.storage_location_authorize)
                },
            tint = if (location.isAuthorized) AuthorizedColor else UnauthorizedColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = location.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.storage_location_provider_label, location.providerName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = location.isAuthorized,
            onCheckedChange = { onToggle() },
        )
    }
}

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
                        providerName = "External Storage",
                        authority = "com.android.externalstorage",
                        rootId = "primary",
                        rootDocumentId = "primary:",
                        treeUri = null,
                        isAuthorized = false,
                        availableBytes = null,
                        iconUri = null,
                    ),
                    StorageLocation(
                        id = "com.android.providers.downloads.documents/downloads",
                        name = "Downloads",
                        providerName = "Downloads",
                        authority = "com.android.providers.downloads.documents",
                        rootId = "downloads",
                        rootDocumentId = "downloads",
                        treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
                        isAuthorized = true,
                        availableBytes = null,
                        iconUri = null,
                    ),
                ),
            fileSizeLimitInput = "50",
            fileSizeLimitError = null,
            downloadTimeoutInput = "60",
            downloadTimeoutError = null,
            allowHttpDownloads = false,
            allowUnverifiedHttpsCerts = false,
            isServerRunning = false,
            onToggleLocation = {},
            onFileSizeLimitChange = {},
            onDownloadTimeoutChange = {},
            onAllowHttpDownloadsChange = {},
            onAllowUnverifiedHttpsCertsChange = {},
        )
    }
}
