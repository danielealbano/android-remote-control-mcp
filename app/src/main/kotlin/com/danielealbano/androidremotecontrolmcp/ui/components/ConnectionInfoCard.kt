@file:Suppress("FunctionNaming", "UnusedPrivateMember", "LongMethod", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private const val TOKEN_MASK = "********-****-****-****-************"

@Composable
fun ConnectionInfoCard(
    bindingAddress: BindingAddress,
    ipAddress: String,
    port: Int,
    httpsEnabled: Boolean,
    bearerToken: String,
    onCopyAll: (String) -> Unit,
    tunnelUrl: String? = null,
    onShare: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showToken by remember { mutableStateOf(false) }

    val displayAddress =
        when (bindingAddress) {
            BindingAddress.LOCALHOST -> "127.0.0.1"
            BindingAddress.NETWORK -> ipAddress
        }
    val scheme = if (httpsEnabled) "https" else "http"
    val serverUrl = "$scheme://$displayAddress:$port"
    val displayToken = if (showToken) bearerToken else TOKEN_MASK

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

            ConnectionInfoRow(
                label = stringResource(R.string.connection_info_ip),
                value = displayAddress,
            )
            ConnectionInfoRow(
                label = stringResource(R.string.connection_info_port),
                value = port.toString(),
            )
            ConnectionInfoRow(
                label = stringResource(R.string.connection_info_url),
                value = serverUrl,
            )
            tunnelUrl?.let { url ->
                ConnectionInfoRow(
                    label = stringResource(R.string.remote_access_public_url_label),
                    value = url,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.connection_info_token)}: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            Spacer(modifier = Modifier.height(8.dp))

            val connectionString =
                buildString {
                    val tokenValue = if (showToken) bearerToken else TOKEN_MASK
                    append("URL: $serverUrl\nToken: $tokenValue")
                    tunnelUrl?.let { append("\nPublic URL: $it") }
                }
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
private fun ConnectionInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
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
            tunnelUrl = "https://random-words.trycloudflare.com",
            onCopyAll = {},
            onShare = {},
        )
    }
}
