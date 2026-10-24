@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogIndexEntry
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LogsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOGS_TIME_FORMAT_PATTERN = "MMM d, HH:mm:ss"

/** Chip display order as agreed with the user — NOT enum declaration order. */
private val CHIP_DISPLAY_ORDER =
    listOf(
        ServerLogEntry.Type.SERVER,
        ServerLogEntry.Type.TOOL_CALL,
        ServerLogEntry.Type.TUNNEL,
        ServerLogEntry.Type.OAUTH,
        ServerLogEntry.Type.AUTH,
        ServerLogEntry.Type.CHANNEL,
        ServerLogEntry.Type.SETTINGS,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val selectedTypes by viewModel.selectedTypes.collectAsStateWithLifecycle()
    val filteredIndex by viewModel.filteredIndex.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.server_logs_title)) },
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.server_logs_clear),
                    )
                }
            },
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            CHIP_DISPLAY_ORDER.forEach { type ->
                FilterChip(
                    selected = type in selectedTypes,
                    onClick = { viewModel.toggleType(type) },
                    label = { Text(typeLabel(type)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        if (filteredIndex.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.server_logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(count = filteredIndex.size, key = { filteredIndex[it].cacheKey }) { i ->
                    val ref = filteredIndex[i]
                    val entry by produceState<ServerLogEntry?>(initialValue = null, ref) {
                        value = viewModel.entryAt(ref)
                    }
                    LogEntryRow(ref = ref, entry = entry)
                    HorizontalDivider()
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.server_logs_clear_dialog_title)) },
            text = { Text(stringResource(R.string.server_logs_clear_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLogs()
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.server_logs_clear_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.server_logs_clear_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun LogEntryRow(
    ref: ServerLogIndexEntry,
    entry: ServerLogEntry?,
) {
    val timeFormat = remember { SimpleDateFormat(LOGS_TIME_FORMAT_PATTERN, Locale.getDefault()) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeFormat.format(Date(ref.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = typeLabel(ref.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (ref.type == ServerLogEntry.Type.TOOL_CALL) {
                Text(
                    text = "${ref.durationMs ?: 0}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ref.type == ServerLogEntry.Type.TOOL_CALL) {
            Text(
                text = entry?.toolName ?: "…",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry?.message.isNullOrEmpty()) {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                text = entry?.message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun typeLabel(type: ServerLogEntry.Type): String =
    stringResource(
        when (type) {
            ServerLogEntry.Type.TOOL_CALL -> R.string.server_logs_type_tool_call
            ServerLogEntry.Type.TUNNEL -> R.string.server_logs_type_tunnel
            ServerLogEntry.Type.SERVER -> R.string.server_logs_type_server
            ServerLogEntry.Type.OAUTH -> R.string.server_logs_type_oauth
            ServerLogEntry.Type.AUTH -> R.string.server_logs_type_auth
            ServerLogEntry.Type.CHANNEL -> R.string.server_logs_type_channel
            ServerLogEntry.Type.SETTINGS -> R.string.server_logs_type_settings
            ServerLogEntry.Type.PRIVACY -> R.string.server_logs_type_privacy
        },
    )
