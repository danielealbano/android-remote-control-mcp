package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import java.net.URI

/**
 * Resolved relay endpoints for one tunnel session.
 *
 * @property deviceUrl WebSocket URL the device dials (`<relayUrl>/device`).
 * @property publicUrl HTTP(S) base URL MCP clients use (`wss`→`https`, `ws`→`http`; same authority).
 * @property token Trimmed bearer presented on the device socket.
 */
internal data class RelayTarget(
    val deviceUrl: String,
    val publicUrl: String,
    val token: String,
) {
    companion object {
        const val DEVICE_PATH = "/device"
        private const val SECURE_SCHEME = "wss"
        private val WS_SCHEMES = setOf("ws", SECURE_SCHEME)

        /** Defensive re-check of what `RelaySettings` validation guarantees; providers never read settings. */
        fun resolve(config: ServerConfig): Result<RelayTarget> {
            val base = config.relayUrl.trim().trimEnd('/')
            val token = config.relayDeviceToken.trim()
            val uri = runCatching { URI(base) }.getOrNull()
            return when {
                base.isEmpty() -> failure("Relay URL is required")
                token.isEmpty() -> failure("Relay device token is required")
                token.length < ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH ->
                    failure("Relay device token must be at least ${ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH} characters")
                uri == null -> failure("Relay URL is not a valid URI")
                uri.scheme?.lowercase() !in WS_SCHEMES || uri.host.isNullOrEmpty() ->
                    failure("Relay URL must be ws:// or wss:// with a host")
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                    failure("Relay URL must not contain credentials, query or fragment")
                uri.rawPath.isNotEmpty() -> failure("Relay URL must not contain a path")
                else -> Result.success(RelayTarget(base + DEVICE_PATH, publicUrlFor(uri), token))
            }
        }

        private fun publicUrlFor(uri: URI): String {
            val scheme = if (uri.scheme.equals(SECURE_SCHEME, ignoreCase = true)) "https" else "http"
            return "$scheme://${uri.rawAuthority}"
        }

        private fun failure(message: String): Result<RelayTarget> = Result.failure(IllegalArgumentException(message))
    }
}
