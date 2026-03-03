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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val hostnameInput by viewModel.hostnameInput.collectAsStateWithLifecycle()
    val hostnameError by viewModel.hostnameError.collectAsStateWithLifecycle()

    val isEnabled =
        serverStatus !is ServerStatus.Running &&
            serverStatus !is ServerStatus.Starting

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_security_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
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
                    checked = serverConfig.httpsEnabled,
                    onCheckedChange = viewModel::updateHttpsEnabled,
                    enabled = isEnabled,
                )
            }

            // HTTPS Certificate Section (visible only when HTTPS is enabled)
            AnimatedVisibility(visible = serverConfig.httpsEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.config_certificate_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Certificate source selector
                    Column(modifier = Modifier.selectableGroup()) {
                        CertificateSource.entries.forEach { source ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = source == serverConfig.certificateSource,
                                        onClick = { viewModel.updateCertificateSource(source) },
                                        role = Role.RadioButton,
                                        enabled = isEnabled,
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = source == serverConfig.certificateSource,
                                    onClick = null,
                                    enabled = isEnabled,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (source) {
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

                    if (serverConfig.certificateSource == CertificateSource.AUTO_GENERATED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = hostnameInput,
                            onValueChange = viewModel::updateCertificateHostname,
                            label = { Text(stringResource(R.string.config_hostname_label)) },
                            isError = hostnameError != null,
                            supportingText = hostnameError?.let { { Text(it) } },
                            singleLine = true,
                            enabled = isEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
