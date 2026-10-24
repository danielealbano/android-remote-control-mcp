@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccessViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessSettingsScreen(
    onBack: () -> Unit,
    onNavigateClients: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccessViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val publicUrlOverride by viewModel.publicUrlOverrideInput.collectAsStateWithLifecycle()
    val publicUrlError by viewModel.publicUrlOverrideError.collectAsStateWithLifecycle()
    val showDisableDialog by viewModel.showDisableAuthDialog.collectAsStateWithLifecycle()

    var showToken by remember { mutableStateOf(false) }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDisableAuthDialog,
            title = { Text(stringResource(R.string.access_disable_auth_dialog_title)) },
            text = { Text(stringResource(R.string.access_disable_auth_dialog_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDisableLastAuth) {
                    Text(stringResource(R.string.access_disable_auth_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDisableAuthDialog) {
                    Text(stringResource(R.string.access_disable_auth_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.access_title)) },
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
            if (!serverConfig.oauthEnabled && !serverConfig.bearerTokenEnabled) {
                WarningCard(
                    title = stringResource(R.string.access_no_auth_warning_title),
                    body = stringResource(R.string.access_no_auth_warning_body),
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (serverConfig.tunnelEnabled) {
                    WarningCard(
                        title = stringResource(R.string.access_internet_exposure_warning_title),
                        body = stringResource(R.string.access_internet_exposure_warning_body),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // OAuth toggle
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.access_oauth_label), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.access_oauth_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = serverConfig.oauthEnabled,
                    onCheckedChange = viewModel::requestSetOauthEnabled,
                )
            }

            if (serverConfig.oauthEnabled) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.access_connected_clients)) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onNavigateClients),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bearer toggle
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.access_bearer_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = serverConfig.bearerTokenEnabled,
                    onCheckedChange = viewModel::requestSetBearerTokenEnabled,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = serverConfig.bearerToken,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                enabled = serverConfig.bearerTokenEnabled,
                label = { Text(stringResource(R.string.access_bearer_token_field_label)) },
                visualTransformation =
                    if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription =
                                    if (showToken) {
                                        stringResource(R.string.access_token_hide)
                                    } else {
                                        stringResource(R.string.access_token_show)
                                    },
                            )
                        }
                        IconButton(
                            onClick = { viewModel.copyBearerToken(context) },
                            enabled = serverConfig.bearerToken.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.access_token_copy),
                            )
                        }
                        IconButton(
                            onClick = viewModel::regenerateBearerToken,
                            enabled = serverConfig.bearerTokenEnabled,
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.access_token_regenerate),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Public URL override
            OutlinedTextField(
                value = publicUrlOverride,
                onValueChange = viewModel::setPublicUrlOverride,
                label = { Text(stringResource(R.string.access_public_url_label)) },
                supportingText = {
                    Text(publicUrlError ?: stringResource(R.string.access_public_url_supporting))
                },
                isError = publicUrlError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
