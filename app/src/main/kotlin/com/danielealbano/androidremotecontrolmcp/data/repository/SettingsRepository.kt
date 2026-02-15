package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
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
     * Validates a download timeout value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated timeout, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDownloadTimeout(seconds: Int): Result<Int>

    /**
     * Returns the map of authorized storage location IDs to their tree URI strings.
     */
    suspend fun getAuthorizedLocations(): Map<String, String>

    /**
     * Adds an authorized storage location with its tree URI.
     *
     * @param locationId The storage location identifier ("{authority}/{rootId}").
     * @param treeUri The granted document tree URI string.
     */
    suspend fun addAuthorizedLocation(locationId: String, treeUri: String)

    /**
     * Removes an authorized storage location.
     *
     * @param locationId The storage location identifier.
     */
    suspend fun removeAuthorizedLocation(locationId: String)
}
