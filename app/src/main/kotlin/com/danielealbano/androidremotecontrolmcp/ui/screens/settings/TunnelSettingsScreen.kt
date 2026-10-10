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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
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
    val cloudflareTokenInput by viewModel.cloudflareTokenInput.collectAsStateWithLifecycle()
    val cloudflareExtraArgsInput by viewModel.cloudflareExtraArgsInput.collectAsStateWithLifecycle()
    val relayUrlInput by viewModel.relayUrlInput.collectAsStateWithLifecycle()
    val relayUrlError by viewModel.relayUrlError.collectAsStateWithLifecycle()
    val relayUrlInsecure by viewModel.relayUrlInsecure.collectAsStateWithLifecycle()
    val relayDeviceTokenInput by viewModel.relayDeviceTokenInput.collectAsStateWithLifecycle()
    val relayDeviceTokenError by viewModel.relayDeviceTokenError.collectAsStateWithLifecycle()

    val isEnabled =
        serverStatus !is ServerStatus.Running &&
            serverStatus !is ServerStatus.Starting
    // The entire remote-access section is disabled while HTTPS is enabled (a tunnel always
    // targets an http://localhost origin).
    val sectionEnabled = isEnabled && !serverConfig.httpsEnabled

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
            if (serverConfig.httpsEnabled) {
                ElevatedCard(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.remote_access_https_disabled_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

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
                    enabled = sectionEnabled,
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
                                            enabled = sectionEnabled,
                                        ).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = provider == serverConfig.tunnelProvider,
                                    onClick = null,
                                    enabled = sectionEnabled,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text =
                                        when (provider) {
                                            TunnelProviderType.CLOUDFLARE -> {
                                                stringResource(R.string.remote_access_provider_cloudflare)
                                            }

                                            TunnelProviderType.NGROK -> {
                                                stringResource(R.string.remote_access_provider_ngrok)
                                            }

                                            TunnelProviderType.RELAY -> {
                                                stringResource(R.string.remote_access_provider_relay)
                                            }
                                        },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text =
                                        when (provider) {
                                            TunnelProviderType.CLOUDFLARE -> {
                                                stringResource(R.string.remote_access_provider_cloudflare_desc)
                                            }

                                            TunnelProviderType.NGROK -> {
                                                stringResource(R.string.remote_access_provider_ngrok_desc)
                                            }

                                            TunnelProviderType.RELAY -> {
                                                stringResource(R.string.remote_access_provider_relay_desc)
                                            }
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Cloudflare mode selector (Free vs Token)
                    AnimatedVisibility(
                        visible = serverConfig.tunnelProvider == TunnelProviderType.CLOUDFLARE,
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            CloudflareModeSelector(
                                selectedMode = serverConfig.cloudflareTunnelMode,
                                enabled = sectionEnabled,
                                onModeSelected = viewModel::updateCloudflareTunnelMode,
                            )
                        }
                    }

                    // Cloudflare token-mode fields
                    AnimatedVisibility(
                        visible =
                            serverConfig.tunnelProvider == TunnelProviderType.CLOUDFLARE &&
                                serverConfig.cloudflareTunnelMode == CloudflareTunnelMode.TOKEN,
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            CloudflareTokenFields(
                                token = cloudflareTokenInput,
                                serviceUrl = "http://localhost:${serverConfig.port}",
                                enabled = sectionEnabled,
                                onTokenChange = viewModel::updateCloudflareTunnelToken,
                            )
                        }
                    }

                    // Cloudflare extra arguments
                    AnimatedVisibility(
                        visible = serverConfig.tunnelProvider == TunnelProviderType.CLOUDFLARE,
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            CloudflareExtraArgsField(
                                extraArgs = cloudflareExtraArgsInput,
                                enabled = sectionEnabled,
                                onExtraArgsChange = viewModel::updateCloudflareTunnelExtraArgs,
                            )
                        }
                    }

                    // ngrok-specific fields
                    AnimatedVisibility(visible = serverConfig.tunnelProvider == TunnelProviderType.NGROK) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            NgrokConfigFields(
                                authtoken = ngrokAuthtokenInput,
                                domain = ngrokDomainInput,
                                enabled = sectionEnabled,
                                onAuthtokenChange = viewModel::updateNgrokAuthtoken,
                                onDomainChange = viewModel::updateNgrokDomain,
                            )
                        }
                    }

                    // WebSocket relay fields
                    AnimatedVisibility(visible = serverConfig.tunnelProvider == TunnelProviderType.RELAY) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            RelayUrlField(
                                url = relayUrlInput,
                                urlError = relayUrlError,
                                insecure = relayUrlInsecure,
                                enabled = sectionEnabled,
                                onUrlChange = viewModel::updateRelayUrl,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            RelayTokenField(
                                token = relayDeviceTokenInput,
                                tokenError = relayDeviceTokenError,
                                enabled = sectionEnabled,
                                onTokenChange = viewModel::updateRelayDeviceToken,
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
private fun RelayUrlField(
    url: String,
    urlError: String?,
    insecure: Boolean,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
) {
    val insecureWarning = stringResource(R.string.remote_access_relay_insecure_warning)
    val supporting = urlError ?: insecureWarning.takeIf { insecure }

    Column {
        Text(
            text = stringResource(R.string.remote_access_relay_url_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            enabled = enabled,
            isError = urlError != null,
            supportingText = supporting?.let { { Text(it) } },
            placeholder = {
                Text(text = stringResource(R.string.remote_access_relay_url_hint))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RelayTokenField(
    token: String,
    tokenError: String?,
    enabled: Boolean,
    onTokenChange: (String) -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.remote_access_relay_token_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            singleLine = true,
            enabled = enabled,
            isError = tokenError != null,
            supportingText = tokenError?.let { { Text(it) } },
            visualTransformation =
                if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector =
                            if (showToken) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                        contentDescription =
                            if (showToken) {
                                stringResource(R.string.config_token_hide)
                            } else {
                                stringResource(R.string.config_token_show)
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_access_relay_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloudflareModeSelector(
    selectedMode: CloudflareTunnelMode,
    enabled: Boolean,
    onModeSelected: (CloudflareTunnelMode) -> Unit,
) {
    Text(
        text = stringResource(R.string.remote_access_cloudflare_mode_label),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Column(modifier = Modifier.selectableGroup()) {
        CloudflareTunnelMode.entries.forEach { mode ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == selectedMode,
                            onClick = { onModeSelected(mode) },
                            role = Role.RadioButton,
                            enabled = enabled,
                        ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mode == selectedMode,
                    onClick = null,
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (mode) {
                            CloudflareTunnelMode.FREE -> {
                                stringResource(R.string.remote_access_cloudflare_mode_free)
                            }

                            CloudflareTunnelMode.TOKEN -> {
                                stringResource(R.string.remote_access_cloudflare_mode_token)
                            }
                        },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (mode) {
                            CloudflareTunnelMode.FREE -> {
                                stringResource(R.string.remote_access_cloudflare_mode_free_desc)
                            }

                            CloudflareTunnelMode.TOKEN -> {
                                stringResource(R.string.remote_access_cloudflare_mode_token_desc)
                            }
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloudflareTokenFields(
    token: String,
    serviceUrl: String,
    enabled: Boolean,
    onTokenChange: (String) -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column {
        Text(
            text = stringResource(R.string.remote_access_cloudflare_token_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            singleLine = true,
            enabled = enabled,
            visualTransformation =
                if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector =
                            if (showToken) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                        contentDescription =
                            if (showToken) {
                                stringResource(R.string.config_token_hide)
                            } else {
                                stringResource(R.string.config_token_show)
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.remote_access_cloudflare_service_url_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = serviceUrl,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboardManager.setText(AnnotatedString(serviceUrl)) }) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.remote_access_cloudflare_service_url_copy),
                )
            }
        }
    }
}

@Composable
private fun CloudflareExtraArgsField(
    extraArgs: String,
    enabled: Boolean,
    onExtraArgsChange: (String) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.remote_access_cloudflare_extra_args_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = extraArgs,
            onValueChange = onExtraArgsChange,
            singleLine = true,
            enabled = enabled,
            placeholder = {
                Text(text = stringResource(R.string.remote_access_cloudflare_extra_args_hint))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_access_cloudflare_extra_args_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
