package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * [SettingsRepository] implementation backed by Preferences DataStore.
 *
 * This is the single access point for all persisted settings in the
 * application. No other class should access DataStore directly.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 */
@Suppress("TooManyFunctions")
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override val serverConfig: Flow<ServerConfig> =
            dataStore.data.map { prefs ->
                mapPreferencesToServerConfig(prefs)
            }

        override suspend fun getServerConfig(): ServerConfig {
            val config =
                dataStore.data.first().let { prefs ->
                    mapPreferencesToServerConfig(prefs)
                }

            if (config.bearerToken.isEmpty()) {
                val token = generateTokenString()
                updateBearerToken(token)
                return config.copy(bearerToken = token)
            }

            return config
        }

        override suspend fun updatePort(port: Int) {
            dataStore.edit { prefs ->
                prefs[PORT_KEY] = port
            }
        }

        override suspend fun updateBindingAddress(bindingAddress: BindingAddress) {
            dataStore.edit { prefs ->
                prefs[BINDING_ADDRESS_KEY] = bindingAddress.name
            }
        }

        override suspend fun updateBearerToken(token: String) {
            dataStore.edit { prefs ->
                prefs[BEARER_TOKEN_KEY] = token
            }
        }

        override suspend fun generateNewBearerToken(): String {
            val token = generateTokenString()
            updateBearerToken(token)
            return token
        }

        override suspend fun updateAutoStartOnBoot(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[AUTO_START_KEY] = enabled
            }
        }

        override suspend fun updateHttpsEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[HTTPS_ENABLED_KEY] = enabled
            }
        }

        override suspend fun updateCertificateSource(source: CertificateSource) {
            dataStore.edit { prefs ->
                prefs[CERTIFICATE_SOURCE_KEY] = source.name
            }
        }

        override suspend fun updateCertificateHostname(hostname: String) {
            dataStore.edit { prefs ->
                prefs[CERTIFICATE_HOSTNAME_KEY] = hostname
            }
        }

        override suspend fun updateTunnelEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[TUNNEL_ENABLED_KEY] = enabled }
        }

        override suspend fun updateTunnelProvider(provider: TunnelProviderType) {
            dataStore.edit { prefs -> prefs[TUNNEL_PROVIDER_KEY] = provider.name }
        }

        override suspend fun updateNgrokAuthtoken(authtoken: String) {
            dataStore.edit { prefs -> prefs[NGROK_AUTHTOKEN_KEY] = authtoken }
        }

        override suspend fun updateNgrokDomain(domain: String) {
            dataStore.edit { prefs -> prefs[NGROK_DOMAIN_KEY] = domain }
        }

        override fun validatePort(port: Int): Result<Int> =
            if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
                Result.success(port)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}",
                    ),
                )
            }

        @Suppress("ReturnCount")
        override fun validateCertificateHostname(hostname: String): Result<String> {
            if (hostname.isBlank()) {
                return Result.failure(
                    IllegalArgumentException("Certificate hostname must not be empty"),
                )
            }

            if (!HOSTNAME_PATTERN.matches(hostname)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Certificate hostname contains invalid characters. " +
                            "Use only letters, digits, hyphens, and dots.",
                    ),
                )
            }

            return Result.success(hostname)
        }

        /**
         * Maps raw [Preferences] to a [ServerConfig] instance, applying defaults
         * for any missing keys.
         */
        @Suppress("CyclomaticComplexMethod")
        private fun mapPreferencesToServerConfig(prefs: Preferences): ServerConfig {
            val bindingAddressName = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name
            val certificateSourceName = prefs[CERTIFICATE_SOURCE_KEY] ?: CertificateSource.AUTO_GENERATED.name

            val tunnelProviderName = prefs[TUNNEL_PROVIDER_KEY] ?: TunnelProviderType.CLOUDFLARE.name

            return ServerConfig(
                port = prefs[PORT_KEY] ?: ServerConfig.DEFAULT_PORT,
                bindingAddress =
                    BindingAddress.entries.firstOrNull { it.name == bindingAddressName }
                        ?: BindingAddress.LOCALHOST,
                bearerToken = prefs[BEARER_TOKEN_KEY] ?: "",
                autoStartOnBoot = prefs[AUTO_START_KEY] ?: false,
                httpsEnabled = prefs[HTTPS_ENABLED_KEY] ?: false,
                certificateSource =
                    CertificateSource.entries.firstOrNull { it.name == certificateSourceName }
                        ?: CertificateSource.AUTO_GENERATED,
                certificateHostname =
                    prefs[CERTIFICATE_HOSTNAME_KEY]
                        ?: ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME,
                tunnelEnabled = prefs[TUNNEL_ENABLED_KEY] ?: false,
                tunnelProvider =
                    TunnelProviderType.entries.firstOrNull { it.name == tunnelProviderName }
                        ?: TunnelProviderType.CLOUDFLARE,
                ngrokAuthtoken = prefs[NGROK_AUTHTOKEN_KEY] ?: "",
                ngrokDomain = prefs[NGROK_DOMAIN_KEY] ?: "",
            )
        }

        /**
         * Generates a random UUID string for use as a bearer token.
         */
        private fun generateTokenString(): String = UUID.randomUUID().toString()

        companion object {
            private val PORT_KEY = intPreferencesKey("port")
            private val BINDING_ADDRESS_KEY = stringPreferencesKey("binding_address")
            private val BEARER_TOKEN_KEY = stringPreferencesKey("bearer_token")
            private val AUTO_START_KEY = booleanPreferencesKey("auto_start_on_boot")
            private val HTTPS_ENABLED_KEY = booleanPreferencesKey("https_enabled")
            private val CERTIFICATE_SOURCE_KEY = stringPreferencesKey("certificate_source")
            private val CERTIFICATE_HOSTNAME_KEY = stringPreferencesKey("certificate_hostname")
            private val TUNNEL_ENABLED_KEY = booleanPreferencesKey("tunnel_enabled")
            private val TUNNEL_PROVIDER_KEY = stringPreferencesKey("tunnel_provider")
            private val NGROK_AUTHTOKEN_KEY = stringPreferencesKey("ngrok_authtoken")
            private val NGROK_DOMAIN_KEY = stringPreferencesKey("ngrok_domain")

            /**
             * Regex pattern for valid hostnames.
             *
             * Allows labels of letters, digits, and hyphens separated by dots.
             * Each label must start and end with an alphanumeric character.
             * Maximum total length is 253 characters per RFC 1035.
             */
            private val HOSTNAME_PATTERN =
                Regex(
                    "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*" +
                        "[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
                )
        }
    }
