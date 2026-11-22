@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home-screen "Register with" card for [serverConfig], [tunnelStatus] and [serverStatus], followed by its spacing,
 * or nothing once [dismissed]. The Claude button names the connector after the device slug or the phone
 * ([RegisterLinks.connectorName]).
 */
@Composable
fun RegisterWithSection(
    serverConfig: ServerConfig,
    tunnelStatus: TunnelStatus,
    serverStatus: ServerStatus,
    dismissed: Boolean,
    onDismiss: () -> Unit,
) {
    if (dismissed) return
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val state = RegisterLinks.cardState(serverConfig, tunnelStatus, serverStatus)
    RegisterWithCard(
        state = state,
        onRegister = { target ->
            (state as? RegisterCardState.TunnelEnabled)?.mcpUrl?.let { mcpUrl ->
                scope.launch {
                    registerWith(context, clipboard, target, mcpUrl) {
                        RegisterLinks.connectorName(serverConfig.deviceSlug, phoneName(context))
                    }
                }
            }
        },
        onDismiss = onDismiss,
    )
    Spacer(Modifier.height(16.dp))
}

/**
 * The home-screen "Register with" card: without a tunnel only a message that one is needed, else a button per
 * [RegisterTarget], disabled (with a hint) until the public URL is known and while OAuth is off.
 */
@Composable
fun RegisterWithCard(
    state: RegisterCardState,
    onRegister: (RegisterTarget) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalloutCard(
        icon = Icons.Default.Link,
        title = stringResource(R.string.register_card_title),
        modifier = modifier,
        body = registerCardBody(state),
    ) {
        if (state is RegisterCardState.TunnelEnabled) {
            RegisterTarget.entries.forEach { target ->
                val label = stringResource(target.labelRes)
                // Spoken with its context ("Register with Claude"), not only the service name.
                val description = stringResource(R.string.register_button_description, label)
                TextButton(
                    onClick = { onRegister(target) },
                    enabled = state.canRegister,
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text(text = label)
                }
            }
        }
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.register_card_dismiss))
        }
    }
}

/** The card's message or hints ([RegisterCardState.hintsRes]), one per line, or none when every button works. */
@Composable
private fun registerCardBody(state: RegisterCardState): String? =
    state.hintsRes
        .map { stringResource(it) }
        .joinToString("\n")
        .ifEmpty { null }

/**
 * Sends the user to [target]'s add-connector form for [mcpUrl] ([RegisterLinks.link]); Claude's opens filled in, named
 * [connectorName]. ChatGPT's and Gemini's cannot be filled in, so [mcpUrl] is copied first and a message says where to
 * paste it. The link opens in the default browser, because the services' own apps cannot add a connector; with no
 * default browser set, Android picks the app. The system lookups, [connectorName] included, run off the main thread.
 */
internal suspend fun registerWith(
    context: Context,
    clipboard: Clipboard,
    target: RegisterTarget,
    mcpUrl: String,
    connectorName: () -> String,
) {
    val (link, browser) =
        withContext(Dispatchers.IO) { RegisterLinks.link(target, mcpUrl, connectorName) to defaultBrowser(context) }
    if (target.copiesUrl) {
        val label = context.getString(R.string.register_clip_label)
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, mcpUrl)))
    }
    val opened =
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
            browser?.let { intent.setPackage(it) }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app opens the ${target.name} link: ${e.javaClass.simpleName}")
            false
        }
    RegisterLinks.messageAfterOpen(target, opened)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
}

/** The phone's name ([RegisterLinks.phoneName]), read when Claude's button is tapped so a rename is never stale. */
internal fun phoneName(
    context: Context,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
): String {
    val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    return RegisterLinks.phoneName(deviceName, manufacturer, model)
}

/**
 * The default browser's package, or null when none is set. A link with a scheme and no host is handled by browsers
 * only, not by the apps that claim a site's links.
 */
private fun defaultBrowser(context: Context): String? {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
    val packageManager = context.packageManager
    val browsers =
        packageManager
            .queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
    val default =
        packageManager
            .resolveActivity(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            ?.activityInfo
            ?.packageName
    return RegisterLinks.browserPackage(default, browsers)
}

private const val TAG = "MCP:RegisterWith"
