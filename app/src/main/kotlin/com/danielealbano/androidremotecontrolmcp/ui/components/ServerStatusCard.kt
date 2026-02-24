@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300

@Composable
fun ServerStatusCard(
    status: ServerStatus,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor by animateColorAsState(
        targetValue = statusToColor(status, isSystemInDarkTheme()),
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "statusColor",
    )

    val statusText = statusToText(status)
    val isRunning = status is ServerStatus.Running
    val canToggle = status is ServerStatus.Running || status is ServerStatus.Stopped

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.server_status_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(
                        modifier =
                            Modifier
                                .size(STATUS_DOT_SIZE_DP.dp)
                                .semantics {
                                    contentDescription = "Server status indicator: $statusText"
                                },
                    ) {
                        drawCircle(color = statusColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                FilledTonalButton(
                    onClick = if (isRunning) onStopClick else onStartClick,
                    enabled = canToggle,
                ) {
                    Text(
                        text =
                            if (isRunning) {
                                stringResource(R.string.server_action_stop)
                            } else {
                                stringResource(R.string.server_action_start)
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun statusToText(status: ServerStatus): String =
    when (status) {
        is ServerStatus.Running -> stringResource(R.string.server_status_running)
        is ServerStatus.Stopped -> stringResource(R.string.server_status_stopped)
        is ServerStatus.Starting -> stringResource(R.string.server_status_starting)
        is ServerStatus.Stopping -> stringResource(R.string.server_status_stopping)
        is ServerStatus.Error -> stringResource(R.string.server_status_error, status.message)
    }

private fun statusToColor(
    status: ServerStatus,
    isDarkTheme: Boolean,
): Color =
    if (isDarkTheme) {
        when (status) {
            is ServerStatus.Running -> Color(0xFF81C784)
            is ServerStatus.Stopped -> Color(0xFFEF5350)
            is ServerStatus.Starting -> Color(0xFFFFD54F)
            is ServerStatus.Stopping -> Color(0xFFFFD54F)
            is ServerStatus.Error -> Color(0xFFFFB74D)
        }
    } else {
        when (status) {
            is ServerStatus.Running -> Color(0xFF4CAF50)
            is ServerStatus.Stopped -> Color(0xFFF44336)
            is ServerStatus.Starting -> Color(0xFFFFC107)
            is ServerStatus.Stopping -> Color(0xFFFFC107)
            is ServerStatus.Error -> Color(0xFFFF9800)
        }
    }

@Preview(showBackground = true)
@Composable
private fun ServerStatusCardStoppedPreview() {
    AndroidRemoteControlMcpTheme {
        ServerStatusCard(
            status = ServerStatus.Stopped,
            onStartClick = {},
            onStopClick = {},
        )
    }
}
