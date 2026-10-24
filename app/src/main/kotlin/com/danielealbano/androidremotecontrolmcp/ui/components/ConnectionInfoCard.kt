@file:Suppress("FunctionNaming", "UnusedPrivateMember", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.theme.WarningAmber

private const val TOKEN_MASK = "********-****-****-****-************"

/**
 * Visual content of the Public URL row when it is shown.
 *
 * A `null` row content (the return of [tunnelRowContent]) means the row is hidden,
 * so no "hidden" case exists here and the renderer only handles displayable states.
 */
internal sealed interface TunnelRowContent {
    /** Server started and remote access enabled, address not yet available — show a spinner. */
    data object Loading : TunnelRowContent

    /**
     * Tunnel connected — show the public endpoint(s). An EMPTY list means the (token) tunnel is up
     * but has no public hostname configured yet; an endpoint with `valid == false` is flagged.
     */
    data class Connected(
        val endpoints: List<TunnelEndpoint>,
    ) : TunnelRowContent

    /** Tunnel failed — show the error message in red. */
    data class Failed(
        val message: String,
    ) : TunnelRowContent

    /** The free E2E tunnel renews an expired or missing certificate at start: spinner, text and Cancel. */
    data object Renewing : TunnelRowContent

    /** That renewal failed: the reason in red with Retry and Cancel. */
    data class RenewalFailed(
        val message: String,
    ) : TunnelRowContent
}

/**
 * Computes the Public URL row content from the combined server + tunnel state, or
 * `null` when the row must stay hidden (remote access off, or server not Starting/Running).
 *
 * The row becomes visible as soon as the server is Starting or Running and remote
 * access is enabled, showing [TunnelRowContent.Loading] (a spinner) until the tunnel
 * reports [TunnelStatus.Connected] or [TunnelStatus.Error]. A tunnel-start certificate renewal of the free
 * E2E tunnel ([renewal]) takes precedence over the tunnel status.
 */
internal fun tunnelRowContent(
    tunnelEnabled: Boolean,
    serverStatus: ServerStatus,
    tunnelStatus: TunnelStatus,
    renewal: ArcTunnelRenewalState = ArcTunnelRenewalState.Idle,
): TunnelRowContent? {
    val serverActive =
        serverStatus is ServerStatus.Running || serverStatus is ServerStatus.Starting
    if (!tunnelEnabled || !serverActive) return null
    return when (renewal) {
        ArcTunnelRenewalState.Renewing -> TunnelRowContent.Renewing
        is ArcTunnelRenewalState.Failed -> TunnelRowContent.RenewalFailed(renewal.message)
        ArcTunnelRenewalState.Idle -> tunnelStatusRowContent(tunnelStatus)
    }
}

private fun tunnelStatusRowContent(tunnelStatus: TunnelStatus): TunnelRowContent =
    when (tunnelStatus) {
        is TunnelStatus.Connected -> TunnelRowContent.Connected(tunnelStatus.endpoints)
        is TunnelStatus.Error -> TunnelRowContent.Failed(tunnelStatus.message)
        TunnelStatus.Connecting, TunnelStatus.Disconnected -> TunnelRowContent.Loading
    }

/**
 * Builds the Copy-all / Share connection string. One tunnel line is included per
 * connected public endpoint in [tunnelEndpoints] (ALL endpoints, including any flagged as invalid;
 * empty when the tunnel is not connected or has no route); the bearer token line is included only
 * when [bearerToken] is non-empty.
 */
internal fun buildConnectionString(
    serverUrl: String,
    tunnelEndpoints: List<TunnelEndpoint>,
    bearerToken: String,
): String =
    buildString {
        append("URL: $serverUrl")
        tunnelEndpoints.forEach { append("\nTunnel: ${it.url}/mcp") }
        if (bearerToken.isNotEmpty()) {
            append("\nBearer Token: $bearerToken")
        }
    }

@Composable
fun ConnectionInfoCard(
    bindingAddress: BindingAddress,
    ipAddress: String,
    port: Int,
    httpsEnabled: Boolean,
    bearerToken: String,
    onCopyAll: (String) -> Unit,
    tunnelEnabled: Boolean = false,
    serverStatus: ServerStatus = ServerStatus.Stopped,
    tunnelStatus: TunnelStatus = TunnelStatus.Disconnected,
    onShare: (String) -> Unit = {},
    renewal: ArcTunnelRenewalState = ArcTunnelRenewalState.Idle,
    onCancelRenewal: () -> Unit = {},
    onRetryRenewal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showToken by remember { mutableStateOf(false) }

    val displayAddress =
        when (bindingAddress) {
            BindingAddress.LOCALHOST -> "127.0.0.1"
            BindingAddress.NETWORK -> ipAddress
        }
    val scheme = if (httpsEnabled) "https" else "http"
    val serverUrl = "$scheme://$displayAddress:$port/mcp"
    val displayToken = if (showToken) bearerToken else TOKEN_MASK

    val rowContent = tunnelRowContent(tunnelEnabled, serverStatus, tunnelStatus, renewal)

    val labelStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val ipLabel = stringResource(R.string.connection_info_ip)
    val portLabel = stringResource(R.string.connection_info_port)
    val urlLabel = stringResource(R.string.connection_info_url)
    val publicUrlLabel = stringResource(R.string.remote_access_public_url_label)
    val tokenLabel = stringResource(R.string.connection_info_token)

    val visibleLabels =
        buildList {
            add(ipLabel)
            add(portLabel)
            add(urlLabel)
            if (rowContent != null) add(publicUrlLabel)
            if (bearerToken.isNotEmpty()) add(tokenLabel)
        }
    val labelColumnWidth: Dp =
        remember(visibleLabels, labelStyle, density) {
            with(density) {
                visibleLabels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp()
            }
        }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.connection_info_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(12.dp))

            ConnectionInfoRow(label = ipLabel, labelWidth = labelColumnWidth) {
                Text(text = displayAddress, style = MaterialTheme.typography.bodyMedium)
            }
            ConnectionInfoRow(label = portLabel, labelWidth = labelColumnWidth) {
                Text(text = port.toString(), style = MaterialTheme.typography.bodyMedium)
            }
            ConnectionInfoRow(label = urlLabel, labelWidth = labelColumnWidth) {
                Text(text = serverUrl, style = MaterialTheme.typography.bodyMedium)
            }
            if (rowContent != null) {
                ConnectionInfoRow(label = publicUrlLabel, labelWidth = labelColumnWidth) {
                    TunnelRowValue(
                        content = rowContent,
                        onCancelRenewal = onCancelRenewal,
                        onRetryRenewal = onRetryRenewal,
                    )
                }
            }

            if (bearerToken.isNotEmpty()) {
                ConnectionInfoRow(label = tokenLabel, labelWidth = labelColumnWidth) {
                    Text(
                        text = displayToken,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            imageVector =
                                if (showToken) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                            contentDescription =
                                if (showToken) {
                                    stringResource(R.string.config_token_hide)
                                } else {
                                    stringResource(R.string.config_token_show)
                                },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val connectionString =
                buildConnectionString(
                    serverUrl = serverUrl,
                    tunnelEndpoints = (rowContent as? TunnelRowContent.Connected)?.endpoints ?: emptyList(),
                    bearerToken = bearerToken,
                )
            Row(
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = { onCopyAll(connectionString) }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.connection_info_copy_all),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(onClick = { onShare(connectionString) }) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.connection_info_share),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.TunnelRowValue(
    content: TunnelRowContent,
    onCancelRenewal: () -> Unit,
    onRetryRenewal: () -> Unit,
) {
    when (content) {
        TunnelRowContent.Loading -> {
            val connectingDescription = stringResource(R.string.remote_access_status_connecting)
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(16.dp)
                        .semantics { contentDescription = connectingDescription },
                strokeWidth = 2.dp,
            )
        }

        is TunnelRowContent.Connected -> {
            Column(modifier = Modifier.weight(1f)) {
                if (content.endpoints.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.remote_access_no_route_configured),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                } else {
                    content.endpoints.forEach { endpoint ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${endpoint.url}/mcp",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (!endpoint.valid) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription =
                                        stringResource(R.string.remote_access_route_misconfigured),
                                    tint = WarningAmber,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        is TunnelRowContent.Failed -> {
            Text(
                text = stringResource(R.string.remote_access_status_error, content.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }

        TunnelRowContent.Renewing, is TunnelRowContent.RenewalFailed -> {
            Column(modifier = Modifier.weight(1f)) {
                ArcTunnelRenewalBlock(
                    failure = (content as? TunnelRowContent.RenewalFailed)?.message,
                    onCancel = onCancelRenewal,
                    onRetry = onRetryRenewal,
                )
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(
    label: String,
    labelWidth: Dp,
    modifier: Modifier = Modifier,
    value: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        value()
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionInfoCardPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectionInfoCard(
            bindingAddress = BindingAddress.LOCALHOST,
            ipAddress = "192.168.1.100",
            port = 8080,
            httpsEnabled = false,
            bearerToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            tunnelEnabled = true,
            serverStatus = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1"),
            tunnelStatus =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://random-words.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                ),
            onCopyAll = {},
            onShare = {},
        )
    }
}
