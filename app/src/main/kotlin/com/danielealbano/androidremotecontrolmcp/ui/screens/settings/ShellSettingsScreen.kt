@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.SHELL_OUTPUT_MEMORY_CAP_MIB
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ShellPatternKind
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarBackButton
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarTitle
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellLimitField
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellLimitInputError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ShellSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShellSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.shellConfig.collectAsStateWithLifecycle()
    val fields by viewModel.limitFields.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val suStatus by viewModel.suStatus.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { TopBarTitle(stringResource(R.string.settings_shell_title)) },
            navigationIcon = { TopBarBackButton(onClick = onBack) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            ShellElevationSection(
                config = config,
                shizukuState = shizukuState,
                suStatus = suStatus,
                onSwitch = viewModel::setSwitch,
                onGrantShizuku = viewModel::requestShizukuPermission,
                onTestSu = viewModel::testSu,
                onAddAllowlistPattern = { viewModel.addPattern(ShellPatternKind.ALLOWLIST, it) },
                onRemoveAllowlistPattern = { viewModel.removePattern(ShellPatternKind.ALLOWLIST, it) },
            )
            Spacer(Modifier.height(24.dp))
            ShellPatternListEditor(
                titleRes = R.string.shell_blocklist_title,
                descriptionRes = R.string.shell_blocklist_description,
                patterns = config.blocklist,
                onAdd = { viewModel.addPattern(ShellPatternKind.BLOCKLIST, it) },
                onRemove = { viewModel.removePattern(ShellPatternKind.BLOCKLIST, it) },
            )
            Spacer(Modifier.height(24.dp))
            ShellLimitsSection(fields = fields, onChange = viewModel::updateLimit)
        }
    }
}

@Composable
private fun ShellLimitsSection(
    fields: Map<ShellLimit, ShellLimitField>,
    onChange: (ShellLimit, String) -> Unit,
) {
    Text(stringResource(R.string.shell_limits_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.shell_limits_memory_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ShellLimit.entries.forEach { limit ->
        val field = fields[limit] ?: return@forEach
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = field.text,
            onValueChange = { onChange(limit, it) },
            label = { Text(stringResource(limitLabel(limit))) },
            isError = field.error != null,
            supportingText = field.error?.let { error -> { Text(limitErrorText(limit, error)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@StringRes
private fun limitLabel(limit: ShellLimit): Int =
    when (limit) {
        ShellLimit.MAX_RUNNING_PROCESSES -> R.string.shell_limit_max_running_processes
        ShellLimit.MAX_FINISHED_PROCESSES -> R.string.shell_limit_max_finished_processes
        ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES -> R.string.shell_limit_background_timeout_default_minutes
        ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES -> R.string.shell_limit_background_timeout_max_minutes
        ShellLimit.APPROVAL_TIMEOUT_SECONDS -> R.string.shell_limit_approval_timeout_seconds
        ShellLimit.FOREGROUND_WAIT_SECONDS -> R.string.shell_limit_foreground_wait_seconds
        ShellLimit.OUTPUT_PER_CALL_KIB -> R.string.shell_limit_output_per_call_kib
        ShellLimit.OUTPUT_BUFFER_KIB -> R.string.shell_limit_output_buffer_kib
    }

@Composable
private fun limitErrorText(
    limit: ShellLimit,
    error: ShellLimitInputError,
): String {
    val outOfRange = R.string.shell_limit_error_out_of_range
    val overMemory = R.string.shell_limit_error_over_memory_cap
    return when (error) {
        ShellLimitInputError.REQUIRED -> stringResource(R.string.shell_limit_error_required)
        ShellLimitInputError.NOT_A_NUMBER -> stringResource(R.string.shell_limit_error_not_a_number)
        ShellLimitInputError.OUT_OF_RANGE -> stringResource(outOfRange, limit.min, limit.max)
        ShellLimitInputError.ABOVE_MAX -> stringResource(R.string.shell_limit_error_above_max)
        ShellLimitInputError.BELOW_DEFAULT -> stringResource(R.string.shell_limit_error_below_default)
        ShellLimitInputError.OVER_MEMORY_CAP -> stringResource(overMemory, SHELL_OUTPUT_MEMORY_CAP_MIB)
    }
}
