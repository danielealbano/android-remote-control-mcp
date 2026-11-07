@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.PendingElevation
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ElevationApprovalViewModel

@Composable
fun ElevationApprovalScreen(
    requestId: Int,
    onDone: () -> Unit,
    viewModel: ElevationApprovalViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val request = pending.firstOrNull { it.id == requestId }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            Text(stringResource(R.string.elevation_approval_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (request == null) {
                Text(stringResource(R.string.elevation_approval_gone))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.deny(requestId)
                        onDone()
                    },
                ) { Text(stringResource(R.string.elevation_approval_close)) }
            } else {
                ElevationRequestDetails(request)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            viewModel.deny(request.id)
                            onDone()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.elevation_deny)) }
                    Button(
                        onClick = {
                            viewModel.approve(request.id)
                            onDone()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.elevation_approve)) }
                }
            }
        }
    }
}

@Composable
private fun ElevationRequestDetails(request: PendingElevation) {
    Text(stringResource(R.string.elevation_approval_reason_label), style = MaterialTheme.typography.labelLarge)
    Text(request.justification, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.elevation_approval_command_label), style = MaterialTheme.typography.labelLarge)
    SelectionContainer {
        Text(
            request.displayCommand,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
