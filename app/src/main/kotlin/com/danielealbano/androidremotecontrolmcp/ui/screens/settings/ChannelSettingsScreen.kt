@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsScreen(
    viewModel: ChannelViewModel,
    navController: NavHostController,
    onNavigateToNotificationFilter: () -> Unit,
    onNavigateToWifiMonitor: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val config by viewModel.eventChannelConfig.collectAsStateWithLifecycle()
    val endpointUrlInput by viewModel.endpointUrlInput.collectAsStateWithLifecycle()
    val endpointUrlError by viewModel.endpointUrlError.collectAsStateWithLifecycle()
    val authTokenInput by viewModel.authTokenInput.collectAsStateWithLifecycle()
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Channel") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
        ) {
            item {
                OutlinedTextField(
                    value = endpointUrlInput,
                    onValueChange = { viewModel.updateEndpointUrl(it) },
                    label = { Text("Endpoint URL") },
                    isError = endpointUrlError != null,
                    supportingText = endpointUrlError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = authTokenInput,
                    onValueChange = { viewModel.updateAuthToken(it) },
                    label = { Text("Auth Token") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    visualTransformation =
                        if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (tokenVisible) "Hide" else "Show",
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("Auth token", authTokenInput)),
                                        )
                                    }
                                },
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                            IconButton(
                                onClick = { viewModel.generateNewAuthToken() },
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Generate new")
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Auto-start at boot") },
                    supportingContent = { Text("Start event channel when device boots") },
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { viewModel.updateChannelEnabled(it) },
                        )
                    },
                )
            }
            item {
                Text(
                    "Event Sources",
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Notification Events") },
                    leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = config.notifications.enabled,
                                onCheckedChange = { viewModel.updateNotificationChannelEnabled(it) },
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(onClick = onNavigateToNotificationFilter),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("WiFi Events") },
                    leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = config.wifi.enabled,
                                onCheckedChange = { viewModel.updateWifiChannelEnabled(it) },
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.clickable(onClick = onNavigateToWifiMonitor),
                )
            }
            geofenceEventSourceItem(navController)
        }
    }
}
