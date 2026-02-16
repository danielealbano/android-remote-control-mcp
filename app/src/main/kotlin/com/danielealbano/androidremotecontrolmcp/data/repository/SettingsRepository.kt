package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import kotlinx.coroutines.flow.Flow

/**
 * Repository for accessing and persisting MCP server settings.
 *
 * This is the single access point for all application settings.
 * All DataStore access MUST go through this interface. UI, ViewModels,
 * and Services must not access DataStore directly.
 */
@Suppress("TooManyFunctions")
interface SettingsRepository {
    /**
     * Observes the current server configuration. Emits a new [ServerConfig]
     * whenever any setting changes.
     */
    val serverConfig: Flow<ServerConfig>

    /**
     * Returns the current server configuration as a one-shot read.
     * If the bearer token is empty, a new one is auto-generated and persisted.
     */
    suspend fun getServerConfig(): ServerConfig

    /**
     * Updates the server port.
     *
     * @param port The new port value. Must pass [validatePort] first.
     */
    suspend fun updatePort(port: Int)

    /** Updates the network binding address. */
    suspend fun updateBindingAddress(bindingAddress: BindingAddress)

    /**
     * Updates the bearer token used for MCP request authentication.
     *
     * @param token The new bearer token value.
     */
    suspend fun updateBearerToken(token: String)

    /**
     * Generates a new random bearer token (UUID), persists it, and returns
     * the generated value.
     *
     * @return The newly generated bearer token.
     */
    suspend fun generateNewBearerToken(): String

    /** Updates the auto-start-on-boot preference. */
    suspend fun updateAutoStartOnBoot(enabled: Boolean)

    /** Updates the HTTPS enabled toggle. */
    suspend fun updateHttpsEnabled(enabled: Boolean)

    /** Updates the HTTPS certificate source. */
    suspend fun updateCertificateSource(source: CertificateSource)

    /**
     * Updates the hostname used for auto-generated HTTPS certificates.
     *
     * @param hostname The new hostname. Must pass [validateCertificateHostname] first.
     */
    suspend fun updateCertificateHostname(hostname: String)

    /**
     * Validates a port number.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated port, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validatePort(port: Int): Result<Int>

    /**
     * Validates a certificate hostname.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated hostname, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateCertificateHostname(hostname: String): Result<String>

    /** Updates the tunnel enabled toggle. */
    suspend fun updateTunnelEnabled(enabled: Boolean)

    /** Updates the tunnel provider type. */
    suspend fun updateTunnelProvider(provider: TunnelProviderType)

    /** Updates the ngrok authtoken. */
    suspend fun updateNgrokAuthtoken(authtoken: String)

    /** Updates the ngrok domain (optional, empty string means auto-assigned). */
    suspend fun updateNgrokDomain(domain: String)

    /** Updates the file size limit for file operations (in MB). */
    suspend fun updateFileSizeLimit(limitMb: Int)

    /**
     * Validates a file size limit value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated limit, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateFileSizeLimit(limitMb: Int): Result<Int>

    /** Updates whether HTTP (non-HTTPS) downloads are allowed. */
    suspend fun updateAllowHttpDownloads(enabled: Boolean)

    /** Updates whether unverified HTTPS certificates are accepted for downloads. */
    suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean)

    /** Updates the download timeout in seconds. */
    suspend fun updateDownloadTimeout(seconds: Int)

    /**
     * Updates the device slug used for tool name prefix.
     *
     * @param slug The new device slug. Must pass [validateDeviceSlug] first.
     */
    suspend fun updateDeviceSlug(slug: String)

    /**
     * Validates a download timeout value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated timeout, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDownloadTimeout(seconds: Int): Result<Int>

    /**
     * Validates a device slug string.
     *
     * Valid slugs contain only letters (a-z, A-Z), digits (0-9), and underscores.
     * Maximum length is [ServerConfig.MAX_DEVICE_SLUG_LENGTH] characters. Empty is valid.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated slug, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDeviceSlug(slug: String): Result<String>

    /**
     * Data class representing a stored storage location record.
     * This is the persistence format; the full [StorageLocation] includes
     * dynamic fields like [StorageLocation.availableBytes].
     *
     * @property id Unique identifier: "{authority}/{documentId}".
     * @property name Display name of the directory.
     * @property path Human-readable path within the provider.
     * @property description User-provided description.
     * @property treeUri The granted persistent tree URI string.
     * @property allowWrite Whether write operations are allowed for this location.
     * @property allowDelete Whether delete operations are allowed for this location.
     */
    data class StoredLocation(
        val id: String,
        val name: String,
        val path: String,
        val description: String,
        val treeUri: String,
        val allowWrite: Boolean = false,
        val allowDelete: Boolean = false,
    )

    /**
     * Returns all stored storage locations.
     */
    suspend fun getStoredLocations(): List<StoredLocation>

    /**
     * Adds a storage location.
     */
    suspend fun addStoredLocation(location: StoredLocation)

    /**
     * Removes a storage location by ID.
     */
    suspend fun removeStoredLocation(locationId: String)

    /**
     * Updates the description of an existing storage location.
     *
     * @param locationId The storage location identifier.
     * @param description The new description.
     */
    suspend fun updateLocationDescription(
        locationId: String,
        description: String,
    )

    /**
     * Updates whether write operations are allowed for a storage location.
     *
     * @param locationId The storage location identifier.
     * @param allowWrite Whether write operations are allowed.
     */
    suspend fun updateLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    )

    /**
     * Updates whether delete operations are allowed for a storage location.
     *
     * @param locationId The storage location identifier.
     * @param allowDelete Whether delete operations are allowed.
     */
    suspend fun updateLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    )
}
