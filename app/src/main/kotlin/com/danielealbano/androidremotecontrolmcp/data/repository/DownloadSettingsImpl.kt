package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject

internal val ALLOW_HTTP_DOWNLOADS_KEY = booleanPreferencesKey("allow_http_downloads")
internal val ALLOW_UNVERIFIED_HTTPS_KEY = booleanPreferencesKey("allow_unverified_https_certs")
internal val BLOCK_LOCAL_NETWORK_DOWNLOADS_KEY = booleanPreferencesKey("block_local_network_downloads")

/**
 * [DownloadSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl] (which delegates these
 * members here); changes are coalesced-logged through [SettingsChangeLogger].
 */
class DownloadSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : DownloadSettings {
        override suspend fun updateAllowHttpDownloads(enabled: Boolean) =
            updateToggle(ALLOW_HTTP_DOWNLOADS_KEY, enabled, "HTTP downloads", "allowed", "disallowed")

        override suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) =
            updateToggle(ALLOW_UNVERIFIED_HTTPS_KEY, enabled, "Unverified HTTPS certificates", "allowed", "disallowed")

        override suspend fun updateBlockLocalNetworkDownloads(enabled: Boolean) =
            updateToggle(
                BLOCK_LOCAL_NETWORK_DOWNLOADS_KEY,
                enabled,
                "Downloads from local network addresses",
                "blocked",
                "allowed",
            )

        /** Stores [enabled] under [key] (all download switches default to off) and logs the change. */
        private suspend fun updateToggle(
            key: Preferences.Key<Boolean>,
            enabled: Boolean,
            subject: String,
            onWord: String,
            offWord: String,
        ) {
            dataStore.edit { prefs ->
                val old = prefs[key] ?: false
                prefs[key] = enabled
                settingsChangeLogger.submitToggle(key.name, old, enabled, subject, onWord, offWord)
            }
        }
    }
