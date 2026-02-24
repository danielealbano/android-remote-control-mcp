@file:Suppress("FunctionNaming", "UnusedPrivateMember", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val STATUS_INDICATOR_SIZE_DP = 16

@Composable
fun RemoteAccessSection(
    tunnelEnabled: Boolean,
    tunnelProvider: TunnelProviderType,
    ngrokAuthtoken: String,
    ngrokDomain: String,
    tunnelStatus: TunnelStatus,
    isServerRunning: Boolean,
    onTunnelEnabledChange: (Boolean) -> Unit,
    onTunnelProviderChange: (TunnelProviderType) -> Unit,
    onNgrokAuthtokenChange: (String) -> Unit,
    onNgrokDomainChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_access_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.remote_access_enabled_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = tunnelEnabled,
                    onCheckedChange = onTunnelEnabledChange,
                    enabled = !isServerRunning,
                )
            }

            AnimatedVisibility(visible = tunnelEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.remote_access_provider_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TunnelProviderSelector(
                        selected = tunnelProvider,
                        enabled = !isServerRunning,
                        onSelect = onTunnelProviderChange,
                    )

                    AnimatedVisibility(visible = tunnelProvider == TunnelProviderType.NGROK) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            NgrokConfigFields(
                                authtoken = ngrokAuthtoken,
                                domain = ngrokDomain,
                                enabled = !isServerRunning,
                                onAuthtokenChange = onNgrokAuthtokenChange,
                                onDomainChange = onNgrokDomainChange,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    TunnelStatusIndicator(status = tunnelStatus)
                }
            }
        }
    }
}

@Composable
private fun TunnelProviderSelector(
    selected: TunnelProviderType,
    enabled: Boolean,
    onSelect: (TunnelProviderType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
    ) {
        TunnelProviderType.entries.forEach { provider ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = provider == selected,
                            onClick = { onSelect(provider) },
                            role = Role.RadioButton,
                            enabled = enabled,
                        ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = provider == selected,
                    onClick = null,
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (provider) {
                            TunnelProviderType.CLOUDFLARE ->
                                stringResource(R.string.remote_access_provider_cloudflare)
                            TunnelProviderType.NGROK ->
                                stringResource(R.string.remote_access_provider_ngrok)
                        },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (provider) {
                            TunnelProviderType.CLOUDFLARE ->
                                stringResource(R.string.remote_access_provider_cloudflare_desc)
                            TunnelProviderType.NGROK ->
                                stringResource(R.string.remote_access_provider_ngrok_desc)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NgrokConfigFields(
    authtoken: String,
    domain: String,
    enabled: Boolean,
    onAuthtokenChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAuthtoken by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.remote_access_ngrok_authtoken_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = authtoken,
            onValueChange = onAuthtokenChange,
            singleLine = true,
            enabled = enabled,
            visualTransformation =
                if (showAuthtoken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(
                    onClick = { showAuthtoken = !showAuthtoken },
                ) {
                    Icon(
                        imageVector =
                            if (showAuthtoken) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                        contentDescription =
                            if (showAuthtoken) {
                                "Hide authtoken"
                            } else {
                                "Show authtoken"
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.remote_access_ngrok_domain_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = domain,
            onValueChange = onDomainChange,
            singleLine = true,
            enabled = enabled,
            placeholder = {
                Text(text = stringResource(R.string.remote_access_ngrok_domain_hint))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TunnelStatusIndicator(
    status: TunnelStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            is TunnelStatus.Disconnected -> {
                Text(
                    text = stringResource(R.string.remote_access_status_disconnected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TunnelStatus.Connecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(STATUS_INDICATOR_SIZE_DP.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.remote_access_status_connecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            is TunnelStatus.Connected -> {
                Text(
                    text = stringResource(R.string.remote_access_status_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TunnelStatus.Error -> {
                Text(
                    text = stringResource(R.string.remote_access_status_error, status.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteAccessSectionPreview() {
    AndroidRemoteControlMcpTheme {
        RemoteAccessSection(
            tunnelEnabled = true,
            tunnelProvider = TunnelProviderType.CLOUDFLARE,
            ngrokAuthtoken = "",
            ngrokDomain = "",
            tunnelStatus =
                TunnelStatus.Connected(
                    url = "https://random-words.trycloudflare.com",
                    providerType = TunnelProviderType.CLOUDFLARE,
                ),
            isServerRunning = false,
            onTunnelEnabledChange = {},
            onTunnelProviderChange = {},
            onNgrokAuthtokenChange = {},
            onNgrokDomainChange = {},
        )
    }
}
