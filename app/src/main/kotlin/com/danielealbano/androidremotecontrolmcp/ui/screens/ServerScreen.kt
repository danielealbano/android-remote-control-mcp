@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlert
import com.danielealbano.androidremotecontrolmcp.ui.ApprovalActivity
import com.danielealbano.androidremotecontrolmcp.ui.components.BatteryOptimizationCard
import com.danielealbano.androidremotecontrolmcp.ui.components.CalloutCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCard
import com.danielealbano.androidremotecontrolmcp.ui.components.PrivacyModeCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelRenewalViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LogsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PrivacyViewModel
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateToPermissions: () -> Unit,
    onShowAllLogs: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onNavigateToTunnelSettings: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
    privacyViewModel: PrivacyViewModel = hiltViewModel(),
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),
    arcTunnelRenewalViewModel: ArcTunnelRenewalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val logsViewModel: LogsViewModel = hiltViewModel()

    val privacyConfig by privacyViewModel.privacyConfig.collectAsStateWithLifecycle()
    val privacyCardDismissed by privacyViewModel.privacyCardDismissed.collectAsStateWithLifecycle()
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val recentServerLogs by logsViewModel.recentServerLogs.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()
    val pendingApprovalCount by viewModel.pendingApprovalCount.collectAsStateWithLifecycle()

    val channelConfig by channelViewModel.eventChannelConfig.collectAsStateWithLifecycle()
    val channelStatus by channelViewModel.channelConnectionStatus.collectAsStateWithLifecycle()
    val ctAlert by arcTunnelViewModel.ctAlert.collectAsStateWithLifecycle()
    val renewal by arcTunnelRenewalViewModel.renewal.collectAsStateWithLifecycle()

    val deviceIp = remember(context) { NetworkUtils.getDeviceIpAddress(context) ?: "N/A" }
    val copiedToClipboardMessage = stringResource(R.string.copied_to_clipboard)
    var showChannelNotConfiguredDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_server)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            if (!isAccessibilityEnabled) {
                PermissionWarningCard(onClick = onNavigateToPermissions)
                Spacer(Modifier.height(16.dp))
            }

            if (!serverConfig.oauthEnabled && !serverConfig.bearerTokenEnabled) {
                NoAuthWarningCard()
                Spacer(Modifier.height(16.dp))
            }

            if (!isBatteryOptimizationIgnored) {
                BatteryOptimizationCard(
                    onRequestExemption = { viewModel.requestBatteryOptimizationExemption() },
                )
                Spacer(Modifier.height(16.dp))
            }

            if (pendingApprovalCount > 0) {
                PendingApprovalsCard(
                    count = pendingApprovalCount,
                    onClick = { context.startActivity(Intent(context, ApprovalActivity::class.java)) },
                )
                Spacer(Modifier.height(16.dp))
            }

            ArcTunnelCtAlertCard(
                alert = ctAlert,
                onChangeName = onNavigateToTunnelSettings,
                onDismiss = { arcTunnelViewModel.dismissCtAlert() },
            )

            if (serverConfig.bindingAddress == BindingAddress.LOCALHOST && !serverConfig.tunnelEnabled) {
                NetworkAccessSuggestionCard(
                    enabled = serverStatus is ServerStatus.Stopped,
                    onEnableFreeTunnel = {
                        arcTunnelViewModel.enableFreeTunnel()
                        onNavigateToTunnelSettings()
                    },
                    onEnableWifi = {
                        viewModel.updateBindingAddress(BindingAddress.NETWORK)
                        onNavigateToNetworkSettings()
                    },
                    onOtherTunnels = {
                        viewModel.updateTunnelProvider(TunnelProviderType.CLOUDFLARE)
                        onNavigateToTunnelSettings()
                    },
                )
                Spacer(Modifier.height(16.dp))
            }

            if (!privacyConfig.enabled && !privacyCardDismissed) {
                PrivacyModeCard(
                    onSetupClick = onOpenPrivacySettings,
                    onDismissClick = { privacyViewModel.dismissPrivacyCard() },
                )
                Spacer(Modifier.height(16.dp))
            }

            ServerStatusCard(
                serverStatus = serverStatus,
                channelStatus = channelStatus,
                channelEnabled = channelConfig.enabled,
                onMcpStartClick = { viewModel.startServer(context) },
                onMcpStopClick = { viewModel.stopServer(context) },
                onChannelStartClick = {
                    if (channelConfig.endpointUrl.isBlank()) {
                        showChannelNotConfiguredDialog = true
                    } else {
                        channelViewModel.startChannel()
                    }
                },
                onChannelStopClick = { channelViewModel.stopChannel() },
                startEnabled = isAccessibilityEnabled,
            )

            Spacer(Modifier.height(16.dp))

            ConnectionInfoCard(
                bindingAddress = serverConfig.bindingAddress,
                ipAddress = deviceIp,
                port = serverConfig.port,
                httpsEnabled = serverConfig.httpsEnabled,
                bearerToken = serverConfig.bearerToken,
                tunnelEnabled = serverConfig.tunnelEnabled,
                serverStatus = serverStatus,
                tunnelStatus = tunnelStatus,
                renewal = renewal,
                onCancelRenewal = { viewModel.stopServer(context) },
                onRetryRenewal = arcTunnelRenewalViewModel::retry,
                onCopyAll = { text ->
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Server details", text)))
                        Toast.makeText(context, copiedToClipboardMessage, Toast.LENGTH_SHORT).show()
                    }
                },
                onShare = { text ->
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                },
            )

            Spacer(Modifier.height(16.dp))

            ServerLogsSection(
                logs = recentServerLogs,
                onShowMore = onShowAllLogs,
            )
        }
    }

    if (showChannelNotConfiguredDialog) {
        AlertDialog(
            onDismissRequest = { showChannelNotConfiguredDialog = false },
            title = { Text(stringResource(R.string.channel_not_configured_dialog_title)) },
            text = { Text(stringResource(R.string.channel_not_configured_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showChannelNotConfiguredDialog = false }) {
                    Text(stringResource(R.string.channel_not_configured_dialog_ok))
                }
            },
        )
    }
}

@Composable
private fun NoAuthWarningCard() {
    CalloutCard(
        icon = Icons.Default.Warning,
        title = stringResource(R.string.access_no_auth_warning_title),
    )
}

@Composable
private fun NetworkAccessSuggestionCard(
    enabled: Boolean,
    onEnableFreeTunnel: () -> Unit,
    onEnableWifi: () -> Unit,
    onOtherTunnels: () -> Unit,
) {
    CalloutCard(
        icon = Icons.Default.Info,
        title = stringResource(R.string.server_network_access_suggestion_title),
    ) {
        TextButton(onClick = onEnableFreeTunnel, enabled = enabled) {
            Text(stringResource(R.string.server_network_access_suggestion_arc_tunnel))
        }
        TextButton(onClick = onEnableWifi, enabled = enabled) {
            Text(stringResource(R.string.server_network_access_suggestion_wifi))
        }
        TextButton(onClick = onOtherTunnels, enabled = enabled) {
            Text(stringResource(R.string.server_network_access_suggestion_other_tunnels))
        }
    }
}

/** The Certificate Transparency banner; nothing when there is no alert. */
@Composable
private fun ArcTunnelCtAlertCard(
    alert: ArcTunnelCtAlert?,
    onChangeName: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (alert == null) return
    CalloutCard(
        icon = Icons.Default.Warning,
        title = stringResource(R.string.server_arc_tunnel_ct_alert_title),
        body = stringResource(R.string.server_arc_tunnel_ct_alert_body, alert.fqdn),
    ) {
        TextButton(onClick = onChangeName) {
            Text(stringResource(R.string.server_arc_tunnel_ct_alert_change_name))
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.server_arc_tunnel_ct_alert_dismiss))
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PendingApprovalsCard(
    count: Int,
    onClick: () -> Unit,
) {
    CalloutCard(
        icon = Icons.Default.Notifications,
        title = stringResource(R.string.server_pending_approvals_title, count),
    ) {
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.server_pending_approvals_action))
        }
    }
}

@Composable
private fun PermissionWarningCard(onClick: () -> Unit) {
    CalloutCard(
        icon = Icons.Default.Warning,
        title = stringResource(R.string.permission_warning_title),
    ) {
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.permission_warning_action))
        }
    }
}
