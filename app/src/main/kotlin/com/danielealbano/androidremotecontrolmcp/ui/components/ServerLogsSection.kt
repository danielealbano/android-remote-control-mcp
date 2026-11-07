@file:Suppress("FunctionNaming", "UnusedPrivateMember", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TIME_FORMAT_PATTERN = "HH:mm:ss"

@Composable
fun ServerLogsSection(
    logs: List<ServerLogEntry>,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.server_logs_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.server_logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    logs.forEach { entry ->
                        ServerLogEntryRow(entry = entry)
                        HorizontalDivider()
                    }
                }
            }

            // Always shown: an empty recent card must still allow opening the full Logs page.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onShowMore) {
                    Text(stringResource(R.string.server_logs_show_more))
                }
            }
        }
    }
}

@Composable
private fun ServerLogEntryRow(entry: ServerLogEntry) {
    val timeFormat = remember { SimpleDateFormat(TIME_FORMAT_PATTERN, Locale.getDefault()) }
    val timeString = timeFormat.format(Date(entry.timestamp))

    when (entry.type) {
        ServerLogEntry.Type.TOOL_CALL -> {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.toolName ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${entry.durationMs ?: 0}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.message.isNotEmpty()) {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ServerLogEntry.Type.TUNNEL,
        ServerLogEntry.Type.SERVER,
        ServerLogEntry.Type.OAUTH,
        ServerLogEntry.Type.AUTH,
        ServerLogEntry.Type.CHANNEL,
        ServerLogEntry.Type.SETTINGS,
        ServerLogEntry.Type.PRIVACY,
        ServerLogEntry.Type.SHELL,
        -> {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerLogsSectionPreview() {
    AndroidRemoteControlMcpTheme {
        ServerLogsSection(
            logs =
                listOf(
                    ServerLogEntry(
                        timestamp = System.currentTimeMillis(),
                        type = ServerLogEntry.Type.TOOL_CALL,
                        message = "tap",
                        toolName = "tap",
                        durationMs = 42,
                    ),
                    ServerLogEntry(
                        timestamp = System.currentTimeMillis(),
                        type = ServerLogEntry.Type.TUNNEL,
                        message = "Tunnel connected: https://random-words.trycloudflare.com",
                    ),
                ),
            onShowMore = {},
        )
    }
}
