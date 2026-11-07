@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellSwitch
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.SuStatus

@Composable
internal fun ShellElevationSection(
    config: ShellConfig,
    shizukuState: ShizukuState,
    suStatus: SuStatus,
    onSwitch: (ShellSwitch, Boolean) -> Unit,
    onGrantShizuku: () -> Unit,
    onRestartApp: () -> Unit,
    onTestSu: () -> Unit,
    onAddAllowlistPattern: (String) -> Unit,
    onRemoveAllowlistPattern: (String) -> Unit,
) {
    Text(stringResource(R.string.shell_elevation_title), style = MaterialTheme.typography.titleMedium)
    ShellSwitchRow(
        titleRes = R.string.shell_elevation_switch,
        descriptionRes = R.string.shell_elevation_switch_description,
        checked = config.elevationEnabled,
    ) { onSwitch(ShellSwitch.ELEVATION, it) }
    AnimatedVisibility(visible = config.elevationEnabled) {
        Column {
            ShizukuStatusRow(shizukuState, onGrantShizuku, onRestartApp)
            SuStatusRow(suStatus, onTestSu)
            ShellSwitchRow(
                titleRes = R.string.shell_auto_allow_switch,
                descriptionRes = R.string.shell_auto_allow_description,
                checked = config.autoAllowElevation,
            ) { onSwitch(ShellSwitch.AUTO_ALLOW, it) }
            if (!config.autoAllowElevation) {
                ShellPatternListEditor(
                    titleRes = R.string.shell_allowlist_title,
                    descriptionRes = R.string.shell_allowlist_description,
                    patterns = config.allowlist,
                    onAdd = onAddAllowlistPattern,
                    onRemove = onRemoveAllowlistPattern,
                )
            }
        }
    }
}

@Composable
internal fun ShellPatternListEditor(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    patterns: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(descriptionRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (patterns.isEmpty()) {
        Text(stringResource(R.string.shell_patterns_empty), style = MaterialTheme.typography.bodyMedium)
    }
    patterns.forEach { pattern ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(pattern, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            IconButton(onClick = { onRemove(pattern) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.shell_pattern_remove, pattern))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.shell_pattern_input_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onAdd(input)
                input = ""
            },
            enabled = input.isNotBlank(),
        ) { Text(stringResource(R.string.shell_pattern_add)) }
    }
}

@Composable
private fun ShellSwitchRow(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
    }
}

@Composable
private fun ShizukuStatusRow(
    state: ShizukuState,
    onGrant: () -> Unit,
    onRestart: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.shell_shizuku_label), style = MaterialTheme.typography.bodyLarge)
    Text(
        shizukuStatusText(state),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state == ShizukuState.NotGranted) {
        Button(onClick = onGrant) { Text(stringResource(R.string.shell_shizuku_grant)) }
    }
    if (state == ShizukuState.NeedsRestart) {
        Button(onClick = onRestart) { Text(stringResource(R.string.shell_shizuku_restart)) }
    }
}

@Composable
private fun SuStatusRow(
    status: SuStatus,
    onTest: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.shell_su_label), style = MaterialTheme.typography.bodyLarge)
    Text(
        suStatusText(status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (status !is SuStatus.NotFound && status !is SuStatus.Testing) {
        OutlinedButton(onClick = onTest) { Text(stringResource(R.string.shell_su_test)) }
    }
}

@Composable
private fun shizukuStatusText(state: ShizukuState): String =
    when (state) {
        ShizukuState.NotInstalled -> stringResource(R.string.shell_shizuku_not_installed)
        ShizukuState.NotRunning -> stringResource(R.string.shell_shizuku_not_running)
        ShizukuState.NeedsRestart -> stringResource(R.string.shell_shizuku_needs_restart)
        ShizukuState.Outdated -> stringResource(R.string.shell_shizuku_outdated)
        ShizukuState.NotGranted -> stringResource(R.string.shell_shizuku_not_granted)
        is ShizukuState.Ready -> stringResource(R.string.shell_shizuku_ready, state.uid)
    }

@Composable
private fun suStatusText(status: SuStatus): String =
    when (status) {
        SuStatus.NotFound -> stringResource(R.string.shell_su_not_found)
        is SuStatus.Found -> stringResource(R.string.shell_su_found, status.path)
        is SuStatus.Testing -> stringResource(R.string.shell_su_testing, status.path)
        is SuStatus.Works -> stringResource(R.string.shell_su_works, status.path)
        is SuStatus.Fails -> stringResource(R.string.shell_su_fails, status.path, status.reason)
    }
