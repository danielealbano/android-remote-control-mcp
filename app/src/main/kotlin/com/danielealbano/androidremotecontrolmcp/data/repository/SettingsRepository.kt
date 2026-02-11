package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository for accessing and persisting MCP server settings.
 *
 * This is the single access point for all application settings.
 * All DataStore access MUST go through this interface. UI, ViewModels,
 * and Services must not access DataStore directly.
 */
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
}
