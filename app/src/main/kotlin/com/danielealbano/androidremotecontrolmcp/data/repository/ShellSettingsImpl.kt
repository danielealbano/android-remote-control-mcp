package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.SHELL_OUTPUT_MEMORY_CAP_KIB
import com.danielealbano.androidremotecontrolmcp.data.model.SHELL_OUTPUT_MEMORY_CAP_MIB
import com.danielealbano.androidremotecontrolmcp.data.model.SHELL_OUTPUT_MEMORY_LIMITS
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ShellPatternKind
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuProviderSwitch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

internal val SHELL_ELEVATION_ENABLED_KEY = booleanPreferencesKey("shell_elevation_enabled")
internal val SHELL_AUTO_ALLOW_ELEVATION_KEY = booleanPreferencesKey("shell_auto_allow_elevation")
internal val SHELL_BLOCKLIST_KEY = stringPreferencesKey("shell_blocklist")
internal val SHELL_ALLOWLIST_KEY = stringPreferencesKey("shell_allowlist")

private val SHELL_PATTERNS_SERIALIZER = ListSerializer(String.serializer())

internal fun shellLimitKey(limit: ShellLimit): Preferences.Key<Int> = intPreferencesKey(limit.key)

internal fun parseShellPatternsOrNull(json: String): List<String>? =
    runCatching { Json.decodeFromString(SHELL_PATTERNS_SERIALIZER, json) }.getOrNull()

internal fun normalizeShellPatterns(patterns: List<String>): List<String> =
    patterns
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun mapShellConfig(prefs: Preferences): ShellConfig =
    ShellConfig(
        elevationEnabled = prefs[SHELL_ELEVATION_ENABLED_KEY] ?: false,
        autoAllowElevation = prefs[SHELL_AUTO_ALLOW_ELEVATION_KEY] ?: false,
        blocklist = prefs[SHELL_BLOCKLIST_KEY]?.let(::parseShellPatternsOrNull).orEmpty(),
        allowlist = prefs[SHELL_ALLOWLIST_KEY]?.let(::parseShellPatternsOrNull).orEmpty(),
        limits =
            ShellLimit.entries
                .mapNotNull { limit -> prefs[shellLimitKey(limit)]?.takeIf(limit::isValid)?.let { limit to it } }
                .toMap(),
    )

internal fun crossLimitError(
    current: ShellConfig,
    limit: ShellLimit,
    value: Int,
): String? {
    val defaultMinutes = current.limit(ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES)
    val maxMinutes = current.limit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES)
    val exceedsMax = limit == ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES && value > maxMinutes
    val belowDefault = limit == ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES && value < defaultMinutes
    val overMemory =
        limit in SHELL_OUTPUT_MEMORY_LIMITS && current.outputMemoryKib(limit, value) > SHELL_OUTPUT_MEMORY_CAP_KIB
    return when {
        exceedsMax -> "Must not exceed the maximum background timeout ($maxMinutes)"
        belowDefault -> "Must not be below the default background timeout ($defaultMinutes)"
        overMemory ->
            "Running plus kept processes times the output kept per process must not exceed " +
                "$SHELL_OUTPUT_MEMORY_CAP_MIB MiB"
        else -> null
    }
}

class ShellSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
        private val shizukuProviderSwitch: ShizukuProviderSwitch,
    ) : ShellSettings {
        override suspend fun updateShellElevationEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[SHELL_ELEVATION_ENABLED_KEY] ?: false
                prefs[SHELL_ELEVATION_ENABLED_KEY] = enabled
                settingsChangeLogger.submitToggle(
                    SHELL_ELEVATION_ENABLED_KEY.name,
                    old,
                    enabled,
                    "Elevated shell commands",
                    "allowed",
                    "disallowed",
                )
                shizukuProviderSwitch.setEnabled(enabled)
            }
        }

        override suspend fun syncShizukuProvider() {
            dataStore.edit { prefs -> shizukuProviderSwitch.setEnabled(prefs[SHELL_ELEVATION_ENABLED_KEY] ?: false) }
        }

        override suspend fun updateShellAutoAllowElevation(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[SHELL_AUTO_ALLOW_ELEVATION_KEY] ?: false
                prefs[SHELL_AUTO_ALLOW_ELEVATION_KEY] = enabled
                settingsChangeLogger.submitToggle(
                    SHELL_AUTO_ALLOW_ELEVATION_KEY.name,
                    old,
                    enabled,
                    "Auto-allow of elevated shell commands",
                )
            }
        }

        override suspend fun updateShellBlocklist(patterns: List<String>) {
            editShellPatterns(ShellPatternKind.BLOCKLIST) { patterns }
        }

        override suspend fun updateShellAllowlist(patterns: List<String>) {
            editShellPatterns(ShellPatternKind.ALLOWLIST) { patterns }
        }

        override suspend fun editShellPatterns(
            kind: ShellPatternKind,
            change: (List<String>) -> List<String>,
        ) {
            val key = if (kind == ShellPatternKind.BLOCKLIST) SHELL_BLOCKLIST_KEY else SHELL_ALLOWLIST_KEY
            val subject = if (kind == ShellPatternKind.BLOCKLIST) "Shell blocklist" else "Shell allowlist"
            dataStore.edit { prefs ->
                val old = prefs[key]?.let(::parseShellPatternsOrNull).orEmpty()
                val normalized = normalizeShellPatterns(change(old))
                prefs[key] = Json.encodeToString(SHELL_PATTERNS_SERIALIZER, normalized)
                settingsChangeLogger.submit(key.name, old.joinToString("\n"), normalized.joinToString("\n")) { _, _ ->
                    "$subject updated (${normalized.size} patterns)"
                }
            }
        }

        override suspend fun updateShellLimit(
            limit: ShellLimit,
            value: Int,
        ): Result<Int> {
            if (!limit.isValid(value)) {
                return Result.failure(IllegalArgumentException("Must be between ${limit.min} and ${limit.max}"))
            }
            var result = Result.success(value)
            dataStore.edit { prefs -> result = applyLimit(prefs, limit, value) }
            return result
        }

        private fun applyLimit(
            prefs: MutablePreferences,
            limit: ShellLimit,
            value: Int,
        ): Result<Int> {
            val current = mapShellConfig(prefs)
            val error = crossLimitError(current, limit, value)
            if (error != null) return Result.failure(IllegalArgumentException(error))
            val old = current.limit(limit)
            prefs[shellLimitKey(limit)] = value
            settingsChangeLogger.submit(limit.key, old.toString(), value.toString()) { o, n ->
                "Shell setting ${limit.key} changed from $o to $n"
            }
            return Result.success(value)
        }
    }
