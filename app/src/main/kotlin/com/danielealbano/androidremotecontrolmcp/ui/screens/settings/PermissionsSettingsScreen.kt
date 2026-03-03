@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils

@Composable
private fun enabledColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF4CAF50)

@Composable
private fun disabledColor(): Color = if (isSystemInDarkTheme()) Color(0xFFEF5350) else Color(0xFFF44336)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSettingsScreen(
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isNotificationPermissionGranted by viewModel.isNotificationPermissionGranted.collectAsStateWithLifecycle()
    val isNotificationListenerEnabled by viewModel.isNotificationListenerEnabled.collectAsStateWithLifecycle()
    val isCameraPermissionGranted by viewModel.isCameraPermissionGranted.collectAsStateWithLifecycle()
    val isMicrophonePermissionGranted by viewModel.isMicrophonePermissionGranted.collectAsStateWithLifecycle()

    // Refresh permissions on ON_RESUME
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshPermissionStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_permissions_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            PermissionRow(
                label = stringResource(R.string.permission_accessibility),
                isEnabled = isAccessibilityEnabled,
                buttonText =
                    if (isAccessibilityEnabled) {
                        stringResource(R.string.permission_enabled)
                    } else {
                        stringResource(R.string.permission_enable)
                    },
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                actionEnabled = !isAccessibilityEnabled,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionRow(
                label = stringResource(R.string.permission_notifications),
                isEnabled = isNotificationPermissionGranted,
                buttonText =
                    if (isNotificationPermissionGranted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.permission_grant)
                    },
                onAction = onRequestNotificationPermission,
                actionEnabled = !isNotificationPermissionGranted,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionRow(
                label = stringResource(R.string.permission_notification_listener),
                isEnabled = isNotificationListenerEnabled,
                buttonText =
                    if (isNotificationListenerEnabled) {
                        stringResource(R.string.permission_enabled)
                    } else {
                        stringResource(R.string.permission_enable)
                    },
                onAction = {
                    PermissionUtils.openNotificationListenerSettings(context)
                },
                actionEnabled = !isNotificationListenerEnabled,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionRow(
                label = stringResource(R.string.permission_camera),
                isEnabled = isCameraPermissionGranted,
                buttonText =
                    if (isCameraPermissionGranted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.permission_grant)
                    },
                onAction = onRequestCameraPermission,
                actionEnabled = !isCameraPermissionGranted,
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionRow(
                label = stringResource(R.string.permission_microphone),
                isEnabled = isMicrophonePermissionGranted,
                buttonText =
                    if (isMicrophonePermissionGranted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.permission_grant)
                    },
                onAction = onRequestMicrophonePermission,
                actionEnabled = !isMicrophonePermissionGranted,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    isEnabled: Boolean,
    buttonText: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription =
                if (isEnabled) {
                    "$label enabled"
                } else {
                    "$label disabled"
                },
            tint = if (isEnabled) enabledColor() else disabledColor(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onAction,
            enabled = actionEnabled,
        ) {
            Text(text = buttonText)
        }
    }
}
