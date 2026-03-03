@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

private const val STATUS_INDICATOR_SIZE_DP = 16

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val ngrokAuthtokenInput by viewModel.ngrokAuthtokenInput.collectAsStateWithLifecycle()
    val ngrokDomainInput by viewModel.ngrokDomainInput.collectAsStateWithLifecycle()

    val isEnabled =
        serverStatus !is ServerStatus.Running &&
            serverStatus !is ServerStatus.Starting

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_tunnel_title)) },
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
            // Tunnel Enable/Disable Toggle
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
                    checked = serverConfig.tunnelEnabled,
                    onCheckedChange = viewModel::updateTunnelEnabled,
                    enabled = isEnabled,
                )
            }

            AnimatedVisibility(visible = serverConfig.tunnelEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Tunnel Provider Selector
                    Text(
                        text = stringResource(R.string.remote_access_provider_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        TunnelProviderType.entries.forEach { provider ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = provider == serverConfig.tunnelProvider,
                                            onClick = { viewModel.updateTunnelProvider(provider) },
                                            role = Role.RadioButton,
                                            enabled = isEnabled,
                                        ).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = provider == serverConfig.tunnelProvider,
                                    onClick = null,
                                    enabled = isEnabled,
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

                    // ngrok-specific fields
                    AnimatedVisibility(visible = serverConfig.tunnelProvider == TunnelProviderType.NGROK) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            NgrokConfigFields(
                                authtoken = ngrokAuthtokenInput,
                                domain = ngrokDomainInput,
                                enabled = isEnabled,
                                onAuthtokenChange = viewModel::updateNgrokAuthtoken,
                                onDomainChange = viewModel::updateNgrokDomain,
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
private fun NgrokConfigFields(
    authtoken: String,
    domain: String,
    enabled: Boolean,
    onAuthtokenChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
) {
    var showAuthtoken by remember { mutableStateOf(false) }

    Column {
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
