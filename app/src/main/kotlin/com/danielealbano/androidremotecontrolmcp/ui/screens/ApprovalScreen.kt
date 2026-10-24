@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.geo.CountryDisplay
import com.danielealbano.androidremotecontrolmcp.geo.GeoLocation
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.ClientIconUrl
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.PendingApproval
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ApprovalViewModel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(
    viewModel: ApprovalViewModel = hiltViewModel(),
    onAllHandled: () -> Unit = {},
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()

    // Auto-close once every request has been handled: trigger only after the list has been non-empty,
    // so a screen opened with nothing pending (e.g. a stale notification) does not immediately finish.
    var hadPending by remember { mutableStateOf(false) }
    LaunchedEffect(pending.isEmpty()) {
        if (pending.isNotEmpty()) {
            hadPending = true
        } else if (hadPending) {
            onAllHandled()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.approval_title)) },
            windowInsets = WindowInsets(0),
        )
        if (pending.isEmpty()) {
            Text(
                text = stringResource(R.string.approval_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val imageLoader = rememberLogoImageLoader()
            LazyColumn(modifier = Modifier.weight(1f).padding(16.dp)) {
                items(pending, key = { it.id }) { approval ->
                    ApprovalCard(
                        approval = approval,
                        imageLoader = imageLoader,
                        onApprove = { viewModel.approve(approval.id) },
                        onDeny = { viewModel.deny(approval.id) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Coil loader with redirects disabled and a short timeout (SSRF hardening for the remote icon fetch). */
@Composable
private fun rememberLogoImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader
            .Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient
                                .Builder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .callTimeout(LOGO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .build()
                        },
                    ),
                )
            }.build()
    }
}

@Composable
private fun ApprovalCard(
    approval: PendingApproval,
    imageLoader: ImageLoader,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val remaining = rememberSecondsRemaining(approval.expiresAtMs)
    val expired = remaining <= 0
    val cardColors =
        if (expired) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClientIcon(approval = approval, imageLoader = imageLoader)
            Spacer(Modifier.height(16.dp))
            Text(
                text = approval.clientName,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            HostChip(host = approval.redirectHost)
            if (approval.clientIp != null) {
                Spacer(Modifier.height(8.dp))
                ClientOriginRow(ip = approval.clientIp, geo = approval.clientGeo)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.approval_match_code_label),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            MatchCodePill(code = approval.matchCode)
            Spacer(Modifier.height(12.dp))
            ExpiryCountdown(remaining = remaining)
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.approval_deny)) }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onApprove, enabled = !expired) { Text(stringResource(R.string.approval_approve)) }
            }
        }
    }
}

/**
 * Circular client icon on a clean light disc (favicons are designed for light backgrounds). The SSRF-safe
 * logo / favicon is inset over muted initials, so a transparent or failed-to-load icon still reads.
 */
@Composable
private fun ClientIcon(
    approval: PendingApproval,
    imageLoader: ImageLoader,
) {
    val context = LocalContext.current
    val initials =
        approval.clientName
            .trim()
            .take(2)
            .uppercase()
            .ifEmpty { "?" }
    val iconUrl = ClientIconUrl.resolve(approval.logoUri, approval.redirectHost)

    Box(
        modifier = Modifier.size(ICON_SIZE_DP.dp).clip(CircleShape).background(ICON_DISC_COLOR),
        contentAlignment = Alignment.Center,
    ) {
        // Initials sit behind; a successfully-loaded icon covers them.
        Text(
            text = initials,
            color = ICON_INITIALS_COLOR,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (iconUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(iconUrl).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier.matchParentSize().padding(ICON_INSET_DP.dp),
            )
        }
    }
}

/** Subtle pill showing the requesting host with a globe glyph. */
@Composable
private fun HostChip(host: String) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(CHIP_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = host,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The request's source IP and its offline geolocation (flag + city + country), when available. */
@Composable
private fun ClientOriginRow(
    ip: String,
    geo: GeoLocation?,
) {
    val flag = CountryDisplay.flag(geo?.countryCode)
    val country = CountryDisplay.name(geo?.countryCode)
    val place = listOfNotNull(geo?.city, country).joinToString(", ")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (place.isNotEmpty()) {
            Text(
                text = if (flag != null) "$flag  $place" else place,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = ip,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Prominent match-code display in a tonal container. */
@Composable
private fun MatchCodePill(code: String) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(PILL_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.displayMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Ticking countdown (seconds) of the approval window; reaches 0 once the request has lapsed. */
@Composable
private fun rememberSecondsRemaining(expiresAtMs: Long): Long {
    var remaining by remember(expiresAtMs) { mutableStateOf(secondsRemaining(expiresAtMs)) }
    LaunchedEffect(expiresAtMs) {
        while (remaining > 0) {
            delay(MILLIS_PER_SECOND)
            remaining = secondsRemaining(expiresAtMs)
        }
    }
    return remaining
}

/** "Expires in m:ss" label; once lapsed it is larger, bold and in the error color for visibility. */
@Composable
private fun ExpiryCountdown(remaining: Long) {
    val expired = remaining <= 0
    val text =
        if (expired) {
            stringResource(R.string.approval_expired)
        } else {
            val mmss = "%d:%02d".format(remaining / SECONDS_PER_MINUTE, remaining % SECONDS_PER_MINUTE)
            stringResource(R.string.approval_expires_in, mmss)
        }
    Text(
        text = text,
        style = if (expired) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (expired) FontWeight.Bold else null,
        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun secondsRemaining(expiresAtMs: Long): Long {
    val remainingMs = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    return remainingMs / MILLIS_PER_SECOND
}

private const val ICON_SIZE_DP = 72
private const val ICON_INSET_DP = 10
private const val CHIP_CORNER_DP = 50
private const val PILL_CORNER_DP = 20
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val ICON_INITIALS_ARGB = 0xFF5B5B66
private val ICON_DISC_COLOR = Color.White
private val ICON_INITIALS_COLOR = Color(ICON_INITIALS_ARGB)
private const val LOGO_TIMEOUT_SECONDS = 5L
