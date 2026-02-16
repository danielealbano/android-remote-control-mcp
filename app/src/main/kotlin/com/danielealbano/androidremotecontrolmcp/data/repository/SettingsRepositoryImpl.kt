package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

        override suspend fun updateFileSizeLimit(limitMb: Int) {
            dataStore.edit { prefs ->
                prefs[FILE_SIZE_LIMIT_KEY] = limitMb
            }
        }

        override fun validateFileSizeLimit(limitMb: Int): Result<Int> =
            if (limitMb in ServerConfig.MIN_FILE_SIZE_LIMIT_MB..ServerConfig.MAX_FILE_SIZE_LIMIT_MB) {
                Result.success(limitMb)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "File size limit must be between ${ServerConfig.MIN_FILE_SIZE_LIMIT_MB} and " +
                            "${ServerConfig.MAX_FILE_SIZE_LIMIT_MB} MB",
                    ),
                )
            }

        override suspend fun updateAllowHttpDownloads(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[ALLOW_HTTP_DOWNLOADS_KEY] = enabled
            }
        }

        override suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[ALLOW_UNVERIFIED_HTTPS_KEY] = enabled
            }
        }

        override suspend fun updateDownloadTimeout(seconds: Int) {
            dataStore.edit { prefs ->
                prefs[DOWNLOAD_TIMEOUT_KEY] = seconds
            }
        }

        override suspend fun updateDeviceSlug(slug: String) {
            dataStore.edit { prefs ->
                prefs[DEVICE_SLUG_KEY] = slug
            }
        }

        override fun validateDownloadTimeout(seconds: Int): Result<Int> =
            if (seconds in ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS) {
                Result.success(seconds)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Download timeout must be between ${ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS} and " +
                            "${ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS} seconds",
                    ),
                )
            }

        @Suppress("ReturnCount")
        override fun validateDeviceSlug(slug: String): Result<String> {
            if (slug.length > ServerConfig.MAX_DEVICE_SLUG_LENGTH) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug must be at most ${ServerConfig.MAX_DEVICE_SLUG_LENGTH} characters",
                    ),
                )
            }
            if (!ServerConfig.DEVICE_SLUG_PATTERN.matches(slug)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug can only contain letters, digits, and underscores",
                    ),
                )
            }
            return Result.success(slug)
        }

        override suspend fun getStoredLocations(): List<SettingsRepository.StoredLocation> {
            val prefs = dataStore.data.first()
            val jsonString = prefs[AUTHORIZED_LOCATIONS_KEY] ?: return emptyList()
            return parseStoredLocationsJson(jsonString)
        }

        override suspend fun addStoredLocation(location: SettingsRepository.StoredLocation) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                existing.add(location)
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
            }
        }

        override suspend fun removeStoredLocation(locationId: String) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                existing.removeAll { it.id == locationId }
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
            }
        }

        override suspend fun updateLocationDescription(
            locationId: String,
            description: String,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    existing[index] = existing[index].copy(description = description)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                }
            }
        }

        override suspend fun updateLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    existing[index] = existing[index].copy(allowWrite = allowWrite)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                } else {
                    Log.w(TAG, "updateLocationAllowWrite: location $locationId not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    existing[index] = existing[index].copy(allowDelete = allowDelete)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                } else {
                    Log.w(TAG, "updateLocationAllowDelete: location $locationId not found, no-op")
                }
            }
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
                fileSizeLimitMb = prefs[FILE_SIZE_LIMIT_KEY] ?: ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
                allowHttpDownloads = prefs[ALLOW_HTTP_DOWNLOADS_KEY] ?: false,
                allowUnverifiedHttpsCerts = prefs[ALLOW_UNVERIFIED_HTTPS_KEY] ?: false,
                downloadTimeoutSeconds =
                    prefs[DOWNLOAD_TIMEOUT_KEY]
                        ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
                deviceSlug = prefs[DEVICE_SLUG_KEY] ?: "",
            )
        }

        /**
         * Generates a random UUID string for use as a bearer token.
         */
        private fun generateTokenString(): String = UUID.randomUUID().toString()

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private fun parseStoredLocationsJson(json: String?): List<SettingsRepository.StoredLocation> {
            if (json == null) return emptyList()
            return try {
                val jsonArray = Json.parseToJsonElement(json).jsonArray
                jsonArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val treeUri = obj["treeUri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val description = obj["description"]?.jsonPrimitive?.content ?: ""
                        val allowWriteElement = obj["allowWrite"]
                        val allowWrite = if (allowWriteElement == null) true else allowWriteElement.jsonPrimitive.booleanOrNull ?: false
                        val allowDeleteElement = obj["allowDelete"]
                        val allowDelete = if (allowDeleteElement == null) true else allowDeleteElement.jsonPrimitive.booleanOrNull ?: false
                        SettingsRepository.StoredLocation(
                            id = id,
                            name = name,
                            path = path,
                            description = description,
                            treeUri = treeUri,
                            allowWrite = allowWrite,
                            allowDelete = allowDelete,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed stored location entry", e)
                        null
                    }
                }
            } catch (_: Exception) {
                // Migration: try parsing old format (JSON object: {"locationId": "treeUri"})
                try {
                    val jsonObject = Json.parseToJsonElement(json).jsonObject
                    jsonObject.map { (key, value) ->
                        SettingsRepository.StoredLocation(
                            id = key,
                            name = key.substringAfterLast("/"),
                            path = "/",
                            description = "",
                            treeUri = value.jsonPrimitive.content,
                            allowWrite = true,
                            allowDelete = true,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse stored locations JSON, returning empty list", e)
                    emptyList()
                }
            }
        }

        private fun serializeStoredLocationsJson(locations: List<SettingsRepository.StoredLocation>): String =
            Json.encodeToString(
                buildJsonArray {
                    for (loc in locations) {
                        add(
                            buildJsonObject {
                                put("id", loc.id)
                                put("name", loc.name)
                                put("path", loc.path)
                                put("description", loc.description)
                                put("treeUri", loc.treeUri)
                                put("allowWrite", loc.allowWrite)
                                put("allowDelete", loc.allowDelete)
                            },
                        )
                    }
                },
            )

        companion object {
            private const val TAG = "MCP:SettingsRepo"
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
            private val FILE_SIZE_LIMIT_KEY = intPreferencesKey("file_size_limit_mb")
            private val ALLOW_HTTP_DOWNLOADS_KEY = booleanPreferencesKey("allow_http_downloads")
            private val ALLOW_UNVERIFIED_HTTPS_KEY = booleanPreferencesKey("allow_unverified_https_certs")
            private val DOWNLOAD_TIMEOUT_KEY = intPreferencesKey("download_timeout_seconds")
            private val DEVICE_SLUG_KEY = stringPreferencesKey("device_slug")
            private val AUTHORIZED_LOCATIONS_KEY = stringPreferencesKey("authorized_storage_locations")

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
