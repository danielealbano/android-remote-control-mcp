package com.danielealbano.androidremotecontrolmcp.data.repository

/**
 * Settings slice for the self-hosted WebSocket relay tunnel provider; [SettingsRepository] delegates to it.
 */
interface RelaySettings {
    /**
     * Updates the WebSocket relay base URL (empty = not configured). Stored trimmed.
     *
     * @param url The new relay URL. Must pass [validateRelayUrl] first.
     */
    suspend fun updateRelayUrl(url: String)

    /**
     * Updates the bearer token the device presents on the relay's `/device` socket (empty clears it). Stored trimmed.
     *
     * @param token The new token. Must pass [validateRelayDeviceToken] first.
     */
    suspend fun updateRelayDeviceToken(token: String)

    /**
     * Validates a WebSocket relay base URL.
     *
     * An empty or blank value IS valid and means "not configured" → [Result.success] with `""`.
     * Otherwise the URL MUST be `ws://host[:port]` or `wss://host[:port]`: a host is required and user-info,
     * path, query and fragment are rejected (the public URL is derived from it and cannot carry a prefix).
     *
     * This is a pure validation function with no I/O; it is intentionally non-suspending.
     *
     * @return [Result.success] with the trimmed URL, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateRelayUrl(url: String): Result<String>

    /**
     * Validates a relay device token: empty is valid (not configured), otherwise the trimmed value MUST have at
     * least [com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH]
     * characters. Pure; non-suspending.
     *
     * @return [Result.success] with the trimmed token, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateRelayDeviceToken(token: String): Result<String>

    /**
     * `true` when [url] uses cleartext `ws://` towards a host that is neither loopback nor RFC 1918 private —
     * the device token and every client credential would travel unencrypted. Pure; `false` for unparsable input.
     */
    fun isRelayUrlInsecure(url: String): Boolean
}
