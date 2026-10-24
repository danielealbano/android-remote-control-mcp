@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.ui.components.ArcTunnelRenewalBlock
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelRenewalViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelUrlState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

private const val PROGRESS_SIZE_DP = 16

data class ArcTunnelSettingsState(
    val tunnelEnabled: Boolean,
    val enabled: Boolean,
    val ctCheckEnabled: Boolean,
    val baseAddress: String,
    val baseAddressValid: Boolean,
    val url: ArcTunnelUrlState,
    val enrollmentState: ArcTunnelEnrollmentState,
    val enrollmentRequested: Boolean,
    val renewal: ArcTunnelRenewalState = ArcTunnelRenewalState.Idle,
)

data class ArcTunnelSettingsActions(
    val onBaseAddressChange: (String) -> Unit,
    val onBaseAddressCommit: () -> Unit,
    val onCtCheckChange: (Boolean) -> Unit,
    val onEnroll: () -> Unit,
    val onCancelEnrollment: () -> Unit,
    val onRotateName: () -> Unit,
    val onCancelRenewal: () -> Unit,
    val onRetryRenewal: () -> Unit,
)

/**
 * The dialog shows only for a pending request, with Remote Access on, nothing enrolled, nothing running,
 * server stopped.
 */
internal fun arcTunnelEnrollDialogVisible(state: ArcTunnelSettingsState): Boolean =
    state.enrollmentRequested &&
        state.tunnelEnabled &&
        state.url is ArcTunnelUrlState.NotEnrolled &&
        state.enrollmentState !is ArcTunnelEnrollmentState.Enrolling &&
        state.enabled

/** "Set up tunnel" covers the enabled-but-not-enrolled idle state (a failure shows Retry instead). */
internal fun arcTunnelSetUpVisible(state: ArcTunnelSettingsState): Boolean =
    state.url is ArcTunnelUrlState.NotEnrolled &&
        state.tunnelEnabled &&
        state.enrollmentState is ArcTunnelEnrollmentState.Idle

/** Stateful entry point: collects the ViewModel and hands plain state + callbacks to [ArcTunnelSettingsContent]. */
@Composable
fun ArcTunnelSettingsSection(
    tunnelEnabled: Boolean,
    enabled: Boolean,
    ctCheckEnabled: Boolean,
    onStopServer: () -> Unit,
    viewModel: ArcTunnelViewModel,
    renewalViewModel: ArcTunnelRenewalViewModel,
) {
    val baseAddress by viewModel.baseAddressInput.collectAsStateWithLifecycle()
    val baseAddressValid by viewModel.baseAddressValid.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    val enrollmentState by viewModel.enrollmentState.collectAsStateWithLifecycle()
    val enrollmentRequested by viewModel.enrollmentRequested.collectAsStateWithLifecycle()
    val renewal by renewalViewModel.renewal.collectAsStateWithLifecycle()
    ArcTunnelSettingsContent(
        state =
            ArcTunnelSettingsState(
                tunnelEnabled = tunnelEnabled,
                enabled = enabled,
                ctCheckEnabled = ctCheckEnabled,
                baseAddress = baseAddress,
                baseAddressValid = baseAddressValid,
                url = url,
                enrollmentState = enrollmentState,
                enrollmentRequested = enrollmentRequested,
                renewal = renewal,
            ),
        actions =
            ArcTunnelSettingsActions(
                onBaseAddressChange = viewModel::updateBaseAddressInput,
                onBaseAddressCommit = viewModel::commitBaseAddress,
                onCtCheckChange = viewModel::updateCtCheckEnabled,
                onEnroll = viewModel::enroll,
                onCancelEnrollment = viewModel::cancelEnrollment,
                onRotateName = viewModel::rotateName,
                onCancelRenewal = onStopServer,
                onRetryRenewal = renewalViewModel::retry,
            ),
    )
}

@Composable
fun ArcTunnelSettingsContent(
    state: ArcTunnelSettingsState,
    actions: ArcTunnelSettingsActions,
) {
    val enrolling = state.enrollmentState is ArcTunnelEnrollmentState.Enrolling
    var showRotateDialog by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.remote_access_arc_tunnel_base_address_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = state.baseAddress,
            onValueChange = actions.onBaseAddressChange,
            singleLine = true,
            enabled = state.enabled,
            isError = !state.baseAddressValid,
            supportingText =
                if (state.baseAddressValid) {
                    null
                } else {
                    { Text(stringResource(R.string.remote_access_arc_tunnel_base_address_error)) }
                },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) actions.onBaseAddressCommit() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_ct_check_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_ct_check_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.ctCheckEnabled, onCheckedChange = actions.onCtCheckChange, enabled = state.enabled)
        }
        Spacer(modifier = Modifier.height(8.dp))
        UrlRow(state, enrolling, onRotate = { showRotateDialog = true }, onEnroll = actions.onEnroll)
        EnrollmentProgress(state.enrollmentState, enabled = state.enabled, onRetry = actions.onEnroll)
        if (state.renewal != ArcTunnelRenewalState.Idle) {
            ArcTunnelRenewalBlock(
                failure = (state.renewal as? ArcTunnelRenewalState.Failed)?.message,
                onCancel = actions.onCancelRenewal,
                onRetry = actions.onRetryRenewal,
            )
        }
    }

    if (arcTunnelEnrollDialogVisible(state)) {
        ConfirmDialog(
            title = R.string.remote_access_arc_tunnel_enroll_dialog_title,
            body = R.string.remote_access_arc_tunnel_enroll_dialog_body,
            onConfirm = actions.onEnroll,
            onDismiss = actions.onCancelEnrollment,
        )
    }
    if (showRotateDialog) {
        ConfirmDialog(
            title = R.string.remote_access_arc_tunnel_rotate_dialog_title,
            body = R.string.remote_access_arc_tunnel_rotate_dialog_body,
            onConfirm = {
                showRotateDialog = false
                actions.onRotateName()
            },
            onDismiss = { showRotateDialog = false },
        )
    }
    LaunchedEffect(state.enabled) { if (!state.enabled) showRotateDialog = false }
}

@Composable
private fun UrlRow(
    state: ArcTunnelSettingsState,
    enrolling: Boolean,
    onRotate: () -> Unit,
    onEnroll: () -> Unit,
) {
    when (val url = state.url) {
        is ArcTunnelUrlState.Loading -> {
            Text(stringResource(R.string.remote_access_arc_tunnel_url_loading))
        }

        is ArcTunnelUrlState.Enrolled -> {
            OutlinedTextField(
                value = url.url,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.remote_access_arc_tunnel_url_label)) },
                trailingIcon = {
                    IconButton(onClick = onRotate, enabled = state.enabled && !enrolling) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.remote_access_arc_tunnel_url_refresh),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ArcTunnelUrlState.NotEnrolled -> {
            if (arcTunnelSetUpVisible(state)) {
                TextButton(onClick = onEnroll, enabled = state.enabled) {
                    Text(stringResource(R.string.remote_access_arc_tunnel_set_up))
                }
            }
        }
    }
}

@Composable
private fun EnrollmentProgress(
    state: ArcTunnelEnrollmentState,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    when (state) {
        is ArcTunnelEnrollmentState.Idle -> {
            // nothing to show
        }

        is ArcTunnelEnrollmentState.Enrolling -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE_DP.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_enrolling),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ArcTunnelEnrollmentState.Failed -> {
            Column {
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_enroll_failed, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry, enabled = enabled) {
                    Text(stringResource(R.string.remote_access_arc_tunnel_retry))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: Int,
    body: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remote_access_arc_tunnel_enroll_dialog_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_access_arc_tunnel_enroll_dialog_cancel))
            }
        },
    )
}
