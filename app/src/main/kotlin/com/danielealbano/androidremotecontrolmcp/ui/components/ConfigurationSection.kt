@file:Suppress("FunctionNaming", "UnusedPrivateMember", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

@Suppress("LongParameterList")
@Composable
fun ConfigurationSection(
    bindingAddress: BindingAddress,
    portInput: String,
    portError: String?,
    bearerToken: String,
    autoStartEnabled: Boolean,
    httpsEnabled: Boolean,
    certificateSource: CertificateSource,
    hostnameInput: String,
    hostnameError: String?,
    isServerRunning: Boolean,
    onBindingAddressChange: (BindingAddress) -> Unit,
    onPortChange: (String) -> Unit,
    onRegenerateToken: () -> Unit,
    onCopyToken: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onHttpsEnabledChange: (Boolean) -> Unit,
    onCertificateSourceChange: (CertificateSource) -> Unit,
    onHostnameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNetworkWarningDialog by remember { mutableStateOf(false) }
    var showBearerToken by remember { mutableStateOf(false) }

    if (showNetworkWarningDialog) {
        NetworkSecurityWarningDialog(
            onConfirm = {
                showNetworkWarningDialog = false
                onBindingAddressChange(BindingAddress.NETWORK)
            },
            onDismiss = {
                showNetworkWarningDialog = false
            },
        )
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.configuration_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Binding Address Selector
            Text(
                text = stringResource(R.string.config_binding_address_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            BindingAddressSelector(
                selected = bindingAddress,
                enabled = !isServerRunning,
                onSelect = { address ->
                    if (address == BindingAddress.NETWORK) {
                        showNetworkWarningDialog = true
                    } else {
                        onBindingAddressChange(address)
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Port Input
            OutlinedTextField(
                value = portInput,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.config_port_label)) },
                isError = portError != null,
                supportingText = portError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !isServerRunning,
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
                value = bearerToken,
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
                            onClick = onCopyToken,
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
                            onClick = onRegenerateToken,
                            enabled = !isServerRunning,
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
                    checked = autoStartEnabled,
                    onCheckedChange = onAutoStartChange,
                    enabled = !isServerRunning,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // HTTPS Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.config_https_enabled_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = httpsEnabled,
                    onCheckedChange = onHttpsEnabledChange,
                    enabled = !isServerRunning,
                )
            }

            // HTTPS Certificate Section (visible only when HTTPS is enabled)
            AnimatedVisibility(visible = httpsEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.config_certificate_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CertificateSourceSelector(
                        selected = certificateSource,
                        enabled = !isServerRunning,
                        onSelect = onCertificateSourceChange,
                    )

                    if (certificateSource == CertificateSource.AUTO_GENERATED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = hostnameInput,
                            onValueChange = onHostnameChange,
                            label = { Text(stringResource(R.string.config_hostname_label)) },
                            isError = hostnameError != null,
                            supportingText = hostnameError?.let { { Text(it) } },
                            singleLine = true,
                            enabled = !isServerRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BindingAddressSelector(
    selected: BindingAddress,
    enabled: Boolean,
    onSelect: (BindingAddress) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = BindingAddress.entries
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth(),
    ) {
        options.forEachIndexed { index, address ->
            SegmentedButton(
                selected = address == selected,
                onClick = { onSelect(address) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                enabled = enabled,
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
}

@Composable
private fun CertificateSourceSelector(
    selected: CertificateSource,
    enabled: Boolean,
    onSelect: (CertificateSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
    ) {
        CertificateSource.entries.forEach { source ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = source == selected,
                            onClick = { onSelect(source) },
                            role = Role.RadioButton,
                            enabled = enabled,
                        ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = source == selected,
                    onClick = null,
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (source) {
                            CertificateSource.AUTO_GENERATED ->
                                stringResource(R.string.config_cert_auto_generated)
                            CertificateSource.CUSTOM ->
                                stringResource(R.string.config_cert_custom)
                        },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun NetworkSecurityWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.network_warning_title))
        },
        text = {
            Text(stringResource(R.string.network_warning_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.network_warning_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.network_warning_cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ConfigurationSectionPreview() {
    AndroidRemoteControlMcpTheme {
        ConfigurationSection(
            bindingAddress = BindingAddress.LOCALHOST,
            portInput = "8080",
            portError = null,
            bearerToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            autoStartEnabled = false,
            httpsEnabled = false,
            certificateSource = CertificateSource.AUTO_GENERATED,
            hostnameInput = "android-mcp.local",
            hostnameError = null,
            isServerRunning = false,
            onBindingAddressChange = {},
            onPortChange = {},
            onRegenerateToken = {},
            onCopyToken = {},
            onAutoStartChange = {},
            onHttpsEnabledChange = {},
            onCertificateSourceChange = {},
            onHostnameChange = {},
        )
    }
}
