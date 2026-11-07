package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.repository.ShellSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.parseShellPatternsOrNull

class ShellAdbConfigApplier(
    private val settings: ShellSettings,
) {
    suspend fun apply(intent: Intent) {
        if (intent.hasExtra(EXTRA_ELEVATION_ENABLED)) {
            settings.updateShellElevationEnabled(intent.getBooleanExtra(EXTRA_ELEVATION_ENABLED, false))
        }
        if (intent.hasExtra(EXTRA_AUTO_ALLOW_ELEVATION)) {
            settings.updateShellAutoAllowElevation(intent.getBooleanExtra(EXTRA_AUTO_ALLOW_ELEVATION, false))
        }
        intent.getStringExtra(EXTRA_BLOCKLIST)?.let {
            applyPatterns(EXTRA_BLOCKLIST, it, settings::updateShellBlocklist)
        }
        intent.getStringExtra(EXTRA_ALLOWLIST)?.let {
            applyPatterns(EXTRA_ALLOWLIST, it, settings::updateShellAllowlist)
        }
        applyLimits(intent)
    }

    private suspend fun applyLimits(intent: Intent) {
        val requested =
            ShellLimit.entries
                .filter { intent.hasExtra(it.key) }
                .associateWith { intent.getIntExtra(it.key, Int.MIN_VALUE) }
        val retry = requested.filterNot { (limit, value) -> settings.updateShellLimit(limit, value).isSuccess }
        retry.forEach { (limit, value) -> applyLimit(limit, value) }
        (requested.keys - retry.keys).forEach { Log.i(TAG, "${it.key} updated to ${requested.getValue(it)}") }
    }

    private suspend fun applyPatterns(
        extra: String,
        json: String,
        update: suspend (List<String>) -> Unit,
    ) {
        val patterns = parseShellPatternsOrNull(json)
        if (patterns == null) {
            Log.w(TAG, "Ignoring invalid $extra (expected a JSON array of strings)")
        } else {
            update(patterns)
            Log.i(TAG, "$extra updated (${patterns.size} patterns)")
        }
    }

    private suspend fun applyLimit(
        limit: ShellLimit,
        value: Int,
    ) {
        settings.updateShellLimit(limit, value).fold(
            onSuccess = { Log.i(TAG, "${limit.key} updated to $it") },
            onFailure = { Log.w(TAG, "Ignoring invalid ${limit.key} $value: ${it.message}") },
        )
    }

    companion object {
        private const val TAG = "MCP:ShellAdbConfig"
        const val EXTRA_ELEVATION_ENABLED = "shell_elevation_enabled"
        const val EXTRA_AUTO_ALLOW_ELEVATION = "shell_auto_allow_elevation"
        const val EXTRA_BLOCKLIST = "shell_blocklist"
        const val EXTRA_ALLOWLIST = "shell_allowlist"
    }
}
