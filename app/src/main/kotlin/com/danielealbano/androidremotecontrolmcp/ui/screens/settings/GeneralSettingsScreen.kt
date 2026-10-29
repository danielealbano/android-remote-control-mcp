@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarBackButton
import com.danielealbano.androidremotecontrolmcp.ui.components.TopBarTitle
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val portError by viewModel.portError.collectAsStateWithLifecycle()
    val specificBindAddressInput by viewModel.specificBindAddressInput.collectAsStateWithLifecycle()
    val listenerCheck by viewModel.listenerCheck.collectAsStateWithLifecycle()
    val deviceSlugInput by viewModel.deviceSlugInput.collectAsStateWithLifecycle()
    val deviceSlugError by viewModel.deviceSlugError.collectAsStateWithLifecycle()
    val screenAwakeTimeoutInput by viewModel.screenAwakeTimeoutInput.collectAsStateWithLifecycle()
    val screenAwakeTimeoutError by viewModel.screenAwakeTimeoutError.collectAsStateWithLifecycle()

    val isEnabled =
        serverStatus !is ServerStatus.Running &&
            serverStatus !is ServerStatus.Starting

    // All networks and a specific IP expose the server beyond the device: both ask for the same confirmation
    var pendingExposedBinding by remember { mutableStateOf<BindingAddress?>(null) }

    pendingExposedBinding?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingExposedBinding = null },
            title = { Text(stringResource(R.string.network_warning_title)) },
            text = { Text(stringResource(R.string.network_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingExposedBinding = null
                    viewModel.updateBindingAddress(pending)
                }) {
                    Text(stringResource(R.string.network_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExposedBinding = null }) {
                    Text(stringResource(R.string.network_warning_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { TopBarTitle(stringResource(R.string.settings_general_title)) },
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
            // Binding Address Selector
            Text(
                text = stringResource(R.string.config_binding_address_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val options = BindingAddress.entries
            // Equal heights: some labels wrap on a phone, the others would be shorter
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                options.forEachIndexed { index, address ->
                    SegmentedButton(
                        modifier = Modifier.fillMaxHeight(),
                        selected = address == serverConfig.bindingAddress,
                        onClick = {
                            if (bindingNeedsExposureWarning(address)) {
                                pendingExposedBinding = address
                            } else {
                                viewModel.updateBindingAddress(address)
                            }
                        },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size,
                            ),
                        enabled = isEnabled,
                    ) {
                        Text(
                            text =
                                when (address) {
                                    BindingAddress.LOCALHOST -> stringResource(R.string.config_binding_localhost)
                                    BindingAddress.NETWORK -> stringResource(R.string.config_binding_network)
                                    BindingAddress.SPECIFIC -> stringResource(R.string.config_binding_specific)
                                },
                        )
                    }
                }
            }

            if (serverConfig.bindingAddress == BindingAddress.SPECIFIC) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = specificBindAddressInput,
                    onValueChange = viewModel::updateSpecificBindAddress,
                    label = { Text(stringResource(R.string.config_specific_ip_label)) },
                    isError = listenerCheck.specificAddressError != null,
                    supportingText = listenerCheck.specificAddressError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    enabled = isEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Port Input
            OutlinedTextField(
                value = portInput,
                onValueChange = viewModel::updatePort,
                label = { Text(stringResource(R.string.config_port_label)) },
                isError = portError != null,
                supportingText = portError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Device Slug Input
            OutlinedTextField(
                value = deviceSlugInput,
                onValueChange = viewModel::updateDeviceSlug,
                label = { Text(stringResource(R.string.config_device_slug_label)) },
                placeholder = { Text(stringResource(R.string.config_device_slug_hint)) },
                isError = deviceSlugError != null,
                supportingText = deviceSlugError?.let { { Text(it) } },
                singleLine = true,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Tool-call Indicator Toggle
            Row(
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = serverConfig.toolCallIndicatorEnabled,
                        enabled = isEnabled,
                        role = Role.Switch,
                        onValueChange = viewModel::updateToolCallIndicatorEnabled,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.config_tool_call_indicator_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.config_tool_call_indicator_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = serverConfig.toolCallIndicatorEnabled,
                    onCheckedChange = null,
                    enabled = isEnabled,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = screenAwakeTimeoutInput,
                onValueChange = viewModel::updateScreenAwakeTimeout,
                label = { Text(stringResource(R.string.config_screen_awake_timeout_label)) },
                supportingText = {
                    Text(
                        screenAwakeTimeoutError ?: stringResource(
                            R.string.config_screen_awake_timeout_description,
                            ServerConfig.DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS,
                            ServerConfig.MIN_SCREEN_AWAKE_TIMEOUT_SECONDS,
                            ServerConfig.MAX_SCREEN_AWAKE_TIMEOUT_SECONDS,
                        ),
                    )
                },
                enabled = isEnabled,
                isError = screenAwakeTimeoutError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-Start Toggle
            Row(
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = serverConfig.autoStartOnBoot,
                        enabled = isEnabled,
                        role = Role.Switch,
                        onValueChange = viewModel::updateAutoStartOnBoot,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.config_auto_start_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = serverConfig.autoStartOnBoot,
                    onCheckedChange = null,
                    enabled = isEnabled,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hide from Recents Toggle
            Row(
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = serverConfig.hideFromRecents,
                        enabled = isEnabled,
                        role = Role.Switch,
                        onValueChange = viewModel::updateHideFromRecents,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.config_hide_from_recents_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.config_hide_from_recents_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = serverConfig.hideFromRecents,
                    onCheckedChange = null,
                    enabled = isEnabled,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }
        }
    }
}

/** Whether choosing [address] asks for the exposure warning first: it exposes the server beyond the device. */
internal fun bindingNeedsExposureWarning(address: BindingAddress): Boolean = address != BindingAddress.LOCALHOST
