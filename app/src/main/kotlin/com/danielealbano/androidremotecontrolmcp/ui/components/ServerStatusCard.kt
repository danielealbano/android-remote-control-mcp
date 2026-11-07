@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "LongMethod", "LongParameterList")

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val STATUS_DOT_SIZE_DP = 12
private const val ANIMATION_DURATION_MS = 300
private const val BUTTON_PROGRESS_SIZE_DP = 16

@Composable
fun ServerStatusCard(
    serverStatus: ServerStatus,
    channelStatus: ChannelConnectionStatus,
    channelEnabled: Boolean,
    onMcpStartClick: () -> Unit,
    onMcpStopClick: () -> Unit,
    onChannelStartClick: () -> Unit,
    onChannelStopClick: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier = Modifier,
    mcpStartBlockedMessage: String? = null,
    channelStatusKnown: Boolean = true,
    mcpBusy: Boolean = false,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.server_services_status_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(12.dp))

            // MCP Server row
            ServiceRow(
                label = stringResource(R.string.server_service_mcp),
                statusText = serverStatusToText(serverStatus),
                statusColor = serverStatusToColor(serverStatus, isSystemInDarkTheme()),
                running = serverStatus is ServerStatus.Running,
                transientStatus = serverStatus is ServerStatus.Starting || serverStatus is ServerStatus.Stopping,
                buttonEnabled = mcpStartStopButtonEnabled(serverStatus, startEnabled, mcpStartBlockedMessage != null),
                onButtonClick = if (serverStatus is ServerStatus.Running) onMcpStopClick else onMcpStartClick,
                busy = mcpBusy,
            )
            if (mcpStartBlockedVisible(serverStatus, mcpStartBlockedMessage)) {
                Text(
                    text = stringResource(R.string.server_start_blocked, mcpStartBlockedMessage.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Event Channel row
            ServiceRow(
                label = stringResource(R.string.event_channel_name),
                statusText = channelStatusToText(channelStatus, channelEnabled),
                statusColor = channelStatusToColor(channelStatus, channelEnabled, isSystemInDarkTheme()),
                running = channelEnabled,
                statusKnown = channelStatusKnown,
                buttonEnabled = channelStartStopButtonEnabled(channelEnabled, startEnabled),
                onButtonClick = if (channelEnabled) onChannelStopClick else onChannelStartClick,
            )
        }
    }
}

/**
 * Whether the MCP Server start/stop button is enabled. Stop (Running) is always enabled; Start (Stopped, or after a
 * failed start) requires [startEnabled] (accessibility granted) and listener settings that do not block it
 * ([startBlocked]); transient states are disabled.
 */
internal fun mcpStartStopButtonEnabled(
    status: ServerStatus,
    startEnabled: Boolean,
    startBlocked: Boolean = false,
): Boolean =
    when (status) {
        is ServerStatus.Running -> true
        is ServerStatus.Stopped, is ServerStatus.Error -> startEnabled && !startBlocked
        else -> false
    }

/** Whether why Start is blocked ([message]) shows: while the server is not running. */
internal fun mcpStartBlockedVisible(
    status: ServerStatus,
    message: String?,
): Boolean = message != null && status !is ServerStatus.Running

/**
 * Whether the Event Channel start/stop button is enabled. Stop (enabled) is always allowed;
 * Start requires [startEnabled] (accessibility granted).
 */
internal fun channelStartStopButtonEnabled(
    channelEnabled: Boolean,
    startEnabled: Boolean,
): Boolean = if (channelEnabled) true else startEnabled

@Composable
private fun ServiceRow(
    label: String,
    statusText: String,
    statusColor: Color,
    running: Boolean,
    statusKnown: Boolean = true,
    transientStatus: Boolean = false,
    buttonEnabled: Boolean,
    onButtonClick: () -> Unit,
    busy: Boolean = false,
) {
    // Spoken: the action and the service ("Stop MCP Server"); the service and its status as one ("MCP Server, Running")
    val buttonDescription =
        stringResource(if (running) R.string.server_service_stop else R.string.server_service_start, label)
    val statusDescription = stringResource(R.string.server_service_status, label, statusText)
    val announceChanges = rememberChangedSinceShown(statusDescription, ready = statusKnown)
    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
        label = "statusColor",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            // Decorative: the status is the text next to it
            Canvas(modifier = Modifier.size(STATUS_DOT_SIZE_DP.dp)) {
                drawCircle(color = animatedColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Read as one node carrying "<label>, <status>" itself. A polite live region from the first status
            // change on (rememberChangedSinceShown), so a change is announced without interrupting and showing the
            // card again reads nothing extra: TalkBack announces a live region when the node's own description
            // changes (Compose reports the change from the node that changed, not from a container). Not during a
            // transient status (Starting, Stopping): only the state it settles on is announced, which TalkBack's
            // optional limit of one announcement per node every 30 s would otherwise drop
            Column(
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = statusDescription
                        if (announceChanges && !transientStatus) {
                            liveRegion = LiveRegionMode.Polite
                        }
                    },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FilledTonalButton(
            onClick = onButtonClick,
            enabled = buttonEnabled,
        ) {
            if (busy) {
                // Decorative: the button still is what it says, and what is going on is written on the screen
                CircularProgressIndicator(
                    modifier = Modifier.size(BUTTON_PROGRESS_SIZE_DP.dp).clearAndSetSemantics {},
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            // On the text: the button reads its content, and the description replaces the bare "Stop"
            Text(
                text = stringResource(if (running) R.string.server_action_stop else R.string.server_action_start),
                modifier = Modifier.semantics { contentDescription = buttonDescription },
            )
        }
    }
}

@Composable
private fun serverStatusToText(status: ServerStatus): String =
    when (status) {
        is ServerStatus.Running -> stringResource(R.string.server_status_running)
        is ServerStatus.Stopped -> stringResource(R.string.server_status_stopped)
        is ServerStatus.Starting -> stringResource(R.string.server_status_starting)
        is ServerStatus.Stopping -> stringResource(R.string.server_status_stopping)
        is ServerStatus.Error -> stringResource(R.string.server_status_error, status.message)
    }

private fun serverStatusToColor(
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

@Composable
private fun channelStatusToText(
    status: ChannelConnectionStatus,
    enabled: Boolean,
): String =
    if (!enabled) {
        stringResource(R.string.server_status_stopped)
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> stringResource(R.string.channel_status_idle)
            is ChannelConnectionStatus.Active -> stringResource(R.string.channel_status_active)
            is ChannelConnectionStatus.Error -> status.message
        }
    }

private fun channelStatusToColor(
    status: ChannelConnectionStatus,
    enabled: Boolean,
    isDarkTheme: Boolean,
): Color =
    if (!enabled) {
        if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFF44336)
    } else {
        when (status) {
            is ChannelConnectionStatus.Idle -> {
                Color.Gray
            }

            is ChannelConnectionStatus.Active -> {
                if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
            }

            is ChannelConnectionStatus.Error -> {
                if (isDarkTheme) Color(0xFFFFB74D) else Color(0xFFFF9800)
            }
        }
    }

@Preview(showBackground = true)
@Composable
private fun ServerStatusCardStoppedPreview() {
    AndroidRemoteControlMcpTheme {
        ServerStatusCard(
            serverStatus = ServerStatus.Stopped,
            channelStatus = ChannelConnectionStatus.Idle,
            channelEnabled = false,
            onMcpStartClick = {},
            onMcpStopClick = {},
            onChannelStartClick = {},
            onChannelStopClick = {},
            startEnabled = true,
        )
    }
}
