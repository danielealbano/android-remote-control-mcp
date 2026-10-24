@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PrivacyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val config by viewModel.privacyConfig.collectAsStateWithLifecycle()
    val status by viewModel.privacyStatus.collectAsStateWithLifecycle()
    val downloadState by viewModel.privacyDownloadState.collectAsStateWithLifecycle()
    val benchmarkEstimate by viewModel.privacyBenchmarkEstimate.collectAsStateWithLifecycle()
    val consentRequired by viewModel.consentRequired.collectAsStateWithLifecycle()
    val enableInProgress by viewModel.privacyEnableInProgress.collectAsStateWithLifecycle()
    val benchmarkRunning by viewModel.privacyBenchmarkRunning.collectAsStateWithLifecycle()

    val downloadInProgress = downloadState is DownloadState.Downloading || downloadState is DownloadState.Verifying

    if (consentRequired) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelConsent() },
            title = { Text(stringResource(R.string.privacy_consent_title)) },
            text = { Text(stringResource(R.string.privacy_consent_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDownloadAndEnable() }) {
                    Text(stringResource(R.string.privacy_consent_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelConsent() }) {
                    Text(stringResource(R.string.privacy_consent_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_privacy_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            // 1. Master enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.privacy_enable_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { requested ->
                        if (requested) {
                            viewModel.requestEnablePrivacyMode()
                        } else {
                            viewModel.disablePrivacyMode()
                        }
                    },
                    // Disabled through the WHOLE enable window (request -> download -> warm-up), not
                    // just while DownloadState reports progress; stays usable during the benchmark,
                    // which finishes and stores its result even if Privacy Mode is toggled off.
                    enabled = !downloadInProgress && !enableInProgress,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 2. Status + download / warm-up / benchmark progress
            PrivacyProgressSection(
                status = status,
                downloadState = downloadState,
                enableInProgress = enableInProgress,
                benchmarkRunning = benchmarkRunning,
                benchmarkEstimate = benchmarkEstimate,
            )

            Spacer(Modifier.height(16.dp))

            // 4. Protected categories
            Text(
                text = stringResource(R.string.privacy_categories_header),
                style = MaterialTheme.typography.titleSmall,
            )
            PiiCategory.entries.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(categoryLabel(category)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = config.isCategoryEnabled(category),
                        onCheckedChange = { enabled -> viewModel.updatePrivacyCategoryEnabled(category, enabled) },
                        // Categories are only editable while Privacy Mode is ON and not mid-enable:
                        // toggling them while disabled or during the download would silently change
                        // what gets protected once the mode comes up.
                        enabled = config.enabled && !downloadInProgress && !enableInProgress,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 5. Redaction style + placeholder format
            Text(
                text = stringResource(R.string.privacy_mode_header),
                style = MaterialTheme.typography.titleSmall,
            )
            Column(Modifier.selectableGroup()) {
                RedactionModeOption(
                    label = stringResource(R.string.privacy_mode_pseudonymize),
                    selected = config.redactionMode == RedactionMode.PSEUDONYMIZE,
                    onSelect = { viewModel.updatePrivacyRedactionMode(RedactionMode.PSEUDONYMIZE) },
                )
                RedactionModeOption(
                    label = stringResource(R.string.privacy_mode_redact),
                    selected = config.redactionMode == RedactionMode.REDACT,
                    onSelect = { viewModel.updatePrivacyRedactionMode(RedactionMode.REDACT) },
                )
            }

            AnimatedVisibility(visible = config.redactionMode == RedactionMode.PSEUDONYMIZE) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.privacy_format_header),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Column(Modifier.selectableGroup()) {
                        RedactionModeOption(
                            label = stringResource(R.string.privacy_format_hashed),
                            selected = config.placeholderFormat == PlaceholderFormat.HASHED,
                            onSelect = { viewModel.updatePrivacyPlaceholderFormat(PlaceholderFormat.HASHED) },
                        )
                        RedactionModeOption(
                            label = stringResource(R.string.privacy_format_numbered),
                            selected = config.placeholderFormat == PlaceholderFormat.NUMBERED,
                            onSelect = { viewModel.updatePrivacyPlaceholderFormat(PlaceholderFormat.NUMBERED) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 6. Disclaimer
            Text(
                text = stringResource(R.string.privacy_disclaimer),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))

            // 7. Measured detection rates
            DetectionRatesSection()
        }
    }
}

@Composable
private fun DetectionRatesSection() {
    Text(
        text = stringResource(R.string.privacy_detection_rates_header),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    PiiCategory.entries
        .map { category -> category to stringResource(categoryLabel(category)) }
        .sortedBy { (_, label) -> label }
        .forEach { (category, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = MEASURED_DETECTION_RATES.getValue(category),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.privacy_detection_rates_caption),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PrivacyProgressSection(
    status: PrivacyModeStatus,
    downloadState: DownloadState,
    enableInProgress: Boolean,
    benchmarkRunning: Boolean,
    benchmarkEstimate: Double?,
) {
    Text(
        text = statusText(status),
        style = MaterialTheme.typography.bodyMedium,
    )
    when (downloadState) {
        is DownloadState.Downloading -> {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { downloadState.progressPercent / PERCENT_DIVISOR },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.privacy_download_progress, downloadState.progressPercent),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is DownloadState.Verifying -> {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.privacy_download_verifying),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is DownloadState.Failed -> {
            Text(
                text = stringResource(R.string.privacy_download_failed, downloadState.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        else -> {
            // Instant feedback for the enable phases DownloadState cannot see: the 1-2 s of
            // connection setup before Downloading fires (Idle) and the model warm-up after the
            // download (Completed).
            if (enableInProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.privacy_enable_preparing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (benchmarkRunning) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.privacy_benchmark_running),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    benchmarkEstimate?.let { estimate ->
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                stringResource(
                    R.string.privacy_benchmark_estimate,
                    formatEstimate(estimate),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RedactionModeOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.height(0.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun statusText(status: PrivacyModeStatus): String =
    when (status) {
        is PrivacyModeStatus.Ready -> stringResource(R.string.privacy_status_ready)
        is PrivacyModeStatus.ReadyDeterministicOnly -> stringResource(R.string.privacy_status_deterministic)
        is PrivacyModeStatus.Unavailable -> stringResource(R.string.privacy_status_unavailable, status.reason)
        is PrivacyModeStatus.Disabled -> stringResource(R.string.privacy_status_disabled)
    }

private fun categoryLabel(category: PiiCategory): Int =
    when (category) {
        PiiCategory.CREDENTIALS -> R.string.privacy_category_credentials
        PiiCategory.CARDS_AND_IBAN -> R.string.privacy_category_cards_iban
        PiiCategory.EMAILS -> R.string.privacy_category_emails
        PiiCategory.PHONE_NUMBERS -> R.string.privacy_category_phones
        PiiCategory.NAMES -> R.string.privacy_category_names
        PiiCategory.ADDRESSES -> R.string.privacy_category_addresses
        PiiCategory.NATIONAL_IDS -> R.string.privacy_category_national_ids
    }

private fun formatEstimate(estimate: Double): String = "%.1f".format(estimate)

private const val PERCENT_DIVISOR = 100f

/**
 * Per-category share of PII items detected by the FULL production pipeline, measured with
 * `make privacy-benchmark` on the UI-shaped corpus B (ui-synthetic, seed 20260803, run 2026-08-03).
 * ABSOLUTE RULE (see CLAUDE.md "Privacy detection effectiveness table"): whenever ANY privacy
 * detection logic changes, the benchmark MUST be re-run and these values updated from report.md.
 */
private val MEASURED_DETECTION_RATES: Map<PiiCategory, String> =
    mapOf(
        PiiCategory.CREDENTIALS to "90.9%",
        PiiCategory.CARDS_AND_IBAN to "75.3%",
        PiiCategory.EMAILS to "100.0%",
        PiiCategory.PHONE_NUMBERS to "84.4%",
        PiiCategory.NAMES to "0.8%",
        PiiCategory.ADDRESSES to "77.9%",
        PiiCategory.NATIONAL_IDS to "93.0%",
    )
