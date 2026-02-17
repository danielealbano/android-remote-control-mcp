@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

private val EnabledColor = Color(0xFF4CAF50)
private val DisabledColor = Color(0xFFF44336)

@Suppress("LongMethod")
@Composable
fun PermissionsSection(
    isAccessibilityEnabled: Boolean,
    isNotificationPermissionGranted: Boolean,
    isCameraPermissionGranted: Boolean,
    isMicrophonePermissionGranted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionRow(
                label = stringResource(R.string.permission_accessibility),
                isEnabled = isAccessibilityEnabled,
                buttonText =
                    if (isAccessibilityEnabled) {
                        stringResource(R.string.permission_enabled)
                    } else {
                        stringResource(R.string.permission_enable)
                    },
                onAction = onOpenAccessibilitySettings,
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
        modifier =
            Modifier
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
            tint = if (isEnabled) EnabledColor else DisabledColor,
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

@Preview(showBackground = true)
@Composable
private fun PermissionsSectionPreview() {
    AndroidRemoteControlMcpTheme {
        PermissionsSection(
            isAccessibilityEnabled = false,
            isNotificationPermissionGranted = false,
            isCameraPermissionGranted = false,
            isMicrophonePermissionGranted = false,
            onOpenAccessibilitySettings = {},
            onRequestNotificationPermission = {},
            onRequestCameraPermission = {},
            onRequestMicrophonePermission = {},
        )
    }
}
