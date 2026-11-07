@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

private const val PROGRESS_SIZE_DP = 16

/**
 * A free E2E tunnel certificate renewal at tunnel start, while it runs and whoever started the server, over the whole
 * app. Cancel aborts the renewal and stops the MCP server, until [cancelEnabled] says the certificate is received and
 * must be installed; Back or a tap outside only hides this ([onHide]), because a renewal can wait a long time and the
 * app stays usable meanwhile. It closes when the renewal ends: a missing or expired certificate that failed goes on in
 * [ArcTunnelRenewalBlock], since resolving it can need another screen, and a saved chain that failed is only logged,
 * because the tunnel starts anyway.
 */
@Composable
fun ArcTunnelRenewalDialog(
    visible: Boolean,
    cancelEnabled: Boolean,
    onHide: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onHide,
        text = { ArcTunnelRenewalProgress() },
        confirmButton = {
            TextButton(onClick = onCancel, enabled = cancelEnabled) {
                Text(stringResource(R.string.remote_access_arc_tunnel_renewal_cancel))
            }
        },
    )
}

/**
 * The free E2E tunnel's tunnel-start certificate renewal: in progress (spinner + Cancel) or failed ([failure],
 * with Retry + Cancel). Cancel aborts the renewal and stops the MCP server, until [cancelEnabled] says the certificate
 * is received and must be installed.
 */
@Composable
fun ArcTunnelRenewalBlock(
    failure: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    cancelEnabled: Boolean = true,
) {
    Column(modifier = modifier) {
        if (failure == null) {
            ArcTunnelRenewalProgress()
        } else {
            Text(
                text = stringResource(R.string.remote_access_arc_tunnel_renewal_failed, failure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row {
            if (failure != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.remote_access_arc_tunnel_retry))
                }
            }
            TextButton(onClick = onCancel, enabled = cancelEnabled) {
                Text(stringResource(R.string.remote_access_arc_tunnel_renewal_cancel))
            }
        }
    }
}

@Composable
private fun ArcTunnelRenewalProgress() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE_DP.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.remote_access_arc_tunnel_renewing),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
