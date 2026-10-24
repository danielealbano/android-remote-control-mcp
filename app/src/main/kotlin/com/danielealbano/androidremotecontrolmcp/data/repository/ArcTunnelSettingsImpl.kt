package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import javax.inject.Inject

internal val ARC_TUNNEL_BASE_ADDRESS_KEY = stringPreferencesKey("arc_tunnel_base_address")
internal val ARC_TUNNEL_CT_CHECK_ENABLED_KEY = booleanPreferencesKey("arc_tunnel_ct_check_enabled")
internal val ARC_TUNNEL_EDGE_OVERRIDE_KEY = stringPreferencesKey("arc_tunnel_edge_override")
internal val ARC_TUNNEL_TRUSTED_CA_PEM_KEY = stringPreferencesKey("arc_tunnel_trusted_ca_pem")

/**
 * [ArcTunnelSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl] (which delegates
 * these members here); user-facing changes are coalesced-logged through [SettingsChangeLogger].
 */
class ArcTunnelSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : ArcTunnelSettings {
        override suspend fun updateArcTunnelBaseAddress(baseAddress: String) {
            dataStore.edit { prefs ->
                val old = prefs[ARC_TUNNEL_BASE_ADDRESS_KEY] ?: ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS
                prefs[ARC_TUNNEL_BASE_ADDRESS_KEY] = baseAddress
                settingsChangeLogger.submit("arc_tunnel_base_address", old, baseAddress) { o, n ->
                    "Free E2E tunnel base address changed $o → $n"
                }
            }
        }

        override suspend fun updateArcTunnelCtCheckEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[ARC_TUNNEL_CT_CHECK_ENABLED_KEY] ?: true
                prefs[ARC_TUNNEL_CT_CHECK_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("arc_tunnel_ct_check_enabled", old.toString(), enabled.toString()) { _, n ->
                    "Free E2E tunnel certificate check ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }

        override suspend fun updateArcTunnelE2EOverride(
            edgeOverride: String,
            trustedCaPem: String,
        ) {
            dataStore.edit { prefs ->
                prefs[ARC_TUNNEL_EDGE_OVERRIDE_KEY] = edgeOverride
                prefs[ARC_TUNNEL_TRUSTED_CA_PEM_KEY] = trustedCaPem
            }
        }
    }
