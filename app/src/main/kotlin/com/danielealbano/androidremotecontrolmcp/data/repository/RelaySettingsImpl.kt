package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import java.net.URI
import javax.inject.Inject

/** Preferences key for the relay base URL; also read by `mapPreferencesToServerConfig` (SettingsRepositoryImpl.kt). */
internal val RELAY_URL_KEY = stringPreferencesKey("relay_url")

/** Preferences key for the relay device token; also read by `mapPreferencesToServerConfig`. */
internal val RELAY_DEVICE_TOKEN_KEY = stringPreferencesKey("relay_device_token")

private const val SECURE_RELAY_SCHEME = "wss"
private val RELAY_SCHEMES = setOf("ws", SECURE_RELAY_SCHEME)

/** Loopback and RFC 1918 literals (optionally bracketed IPv6 loopback) for which cleartext `ws://` is acceptable. */
private val PRIVATE_HOST_PATTERN =
    Regex(
        "^(localhost|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|\\[?::1]?|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
            "192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})$",
        RegexOption.IGNORE_CASE,
    )

/**
 * [RelaySettings] implementation backed by the shared Preferences DataStore.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 * @property settingsChangeLogger Coalescing logger that surfaces settings changes in the server log.
 */
class RelaySettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : RelaySettings {
        override suspend fun updateRelayUrl(url: String) {
            val trimmed = url.trim()
            dataStore.edit { prefs ->
                val old = prefs[RELAY_URL_KEY] ?: ""
                prefs[RELAY_URL_KEY] = trimmed
                settingsChangeLogger.submit("relay_url", old, trimmed) { o, n ->
                    "Relay URL changed ${o.ifEmpty { "(none)" }} → ${n.ifEmpty { "(none)" }}"
                }
            }
        }

        override suspend fun updateRelayDeviceToken(token: String) {
            val trimmed = token.trim()
            dataStore.edit { prefs ->
                val old = prefs[RELAY_DEVICE_TOKEN_KEY] ?: ""
                prefs[RELAY_DEVICE_TOKEN_KEY] = trimmed
                settingsChangeLogger.submit("relay_device_token", old, trimmed) { _, _ -> "Relay device token changed" }
            }
        }

        override fun validateRelayUrl(url: String): Result<String> {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) {
                return Result.success("")
            }
            return runCatching { URI(trimmed) }
                .fold(
                    onSuccess = { validateRelayUri(it, trimmed) },
                    onFailure = { Result.failure(IllegalArgumentException("Invalid relay URL: ${it.message}")) },
                )
        }

        override fun validateRelayDeviceToken(token: String): Result<String> {
            val trimmed = token.trim()
            return when {
                trimmed.isEmpty() -> Result.success("")
                trimmed.length < ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH ->
                    Result.failure(
                        IllegalArgumentException(
                            "Relay device token must be at least " +
                                "${ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH} characters",
                        ),
                    )
                else -> Result.success(trimmed)
            }
        }

        override fun isRelayUrlInsecure(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            return uri.scheme.equals("ws", ignoreCase = true) && !PRIVATE_HOST_PATTERN.matches(host)
        }

        private fun validateRelayUri(
            uri: URI,
            original: String,
        ): Result<String> =
            when {
                uri.scheme?.lowercase() !in RELAY_SCHEMES ->
                    Result.failure(IllegalArgumentException("Relay URL must start with ws:// or wss://"))
                uri.host.isNullOrEmpty() ->
                    Result.failure(IllegalArgumentException("Relay URL must include a valid host"))
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                    Result.failure(IllegalArgumentException("Relay URL must not contain credentials, query or fragment"))
                uri.rawPath.trimEnd('/').isNotEmpty() ->
                    Result.failure(IllegalArgumentException("Relay URL must not contain a path (scheme://host[:port] only)"))
                else -> Result.success(original.trimEnd('/'))
            }
    }
