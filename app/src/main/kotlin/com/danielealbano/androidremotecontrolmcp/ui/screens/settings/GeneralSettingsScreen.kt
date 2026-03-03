@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val portError by viewModel.portError.collectAsStateWithLifecycle()
    val deviceSlugInput by viewModel.deviceSlugInput.collectAsStateWithLifecycle()
    val deviceSlugError by viewModel.deviceSlugError.collectAsStateWithLifecycle()

    val isEnabled =
        serverStatus !is ServerStatus.Running &&
            serverStatus !is ServerStatus.Starting

    var showNetworkWarningDialog by remember { mutableStateOf(false) }
    var showBearerToken by remember { mutableStateOf(false) }

    if (showNetworkWarningDialog) {
        AlertDialog(
            onDismissRequest = { showNetworkWarningDialog = false },
            title = { Text(stringResource(R.string.network_warning_title)) },
            text = { Text(stringResource(R.string.network_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showNetworkWarningDialog = false
                    viewModel.updateBindingAddress(BindingAddress.NETWORK)
                }) {
                    Text(stringResource(R.string.network_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNetworkWarningDialog = false }) {
                    Text(stringResource(R.string.network_warning_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_general_title)) },
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
            // Binding Address Selector
            Text(
                text = stringResource(R.string.config_binding_address_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val options = BindingAddress.entries
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEachIndexed { index, address ->
                    SegmentedButton(
                        selected = address == serverConfig.bindingAddress,
                        onClick = {
                            if (address == BindingAddress.NETWORK) {
                                showNetworkWarningDialog = true
                            } else {
                                viewModel.updateBindingAddress(address)
                            }
                        },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size,
                            ),
                        enabled = isEnabled,
                    ) {
                        Text(
                            text =
                                when (address) {
                                    BindingAddress.LOCALHOST -> stringResource(R.string.config_binding_localhost)
                                    BindingAddress.NETWORK -> stringResource(R.string.config_binding_network)
                                },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Port Input
            OutlinedTextField(
                value = portInput,
                onValueChange = viewModel::updatePort,
                label = { Text(stringResource(R.string.config_port_label)) },
                isError = portError != null,
                supportingText = portError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device Slug Input
            OutlinedTextField(
                value = deviceSlugInput,
                onValueChange = viewModel::updateDeviceSlug,
                label = { Text(stringResource(R.string.config_device_slug_label)) },
                placeholder = { Text(stringResource(R.string.config_device_slug_hint)) },
                isError = deviceSlugError != null,
                supportingText = deviceSlugError?.let { { Text(it) } },
                singleLine = true,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bearer Token Display
            Text(
                text = stringResource(R.string.config_bearer_token_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = serverConfig.bearerToken,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                visualTransformation =
                    if (showBearerToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    Row {
                        IconButton(
                            onClick = { showBearerToken = !showBearerToken },
                        ) {
                            Icon(
                                imageVector =
                                    if (showBearerToken) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                contentDescription =
                                    if (showBearerToken) {
                                        stringResource(R.string.config_token_hide)
                                    } else {
                                        stringResource(R.string.config_token_show)
                                    },
                            )
                        }
                        IconButton(
                            onClick = { viewModel.copyToClipboard(context, serverConfig.bearerToken) },
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Copy bearer token"
                                },
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.config_token_copy),
                            )
                        }
                        IconButton(
                            onClick = viewModel::generateNewBearerToken,
                            enabled = isEnabled,
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Regenerate bearer token"
                                },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.config_token_regenerate),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-Start Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.config_auto_start_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = serverConfig.autoStartOnBoot,
                    onCheckedChange = viewModel::updateAutoStartOnBoot,
                    enabled = isEnabled,
                )
            }
        }
    }
}
