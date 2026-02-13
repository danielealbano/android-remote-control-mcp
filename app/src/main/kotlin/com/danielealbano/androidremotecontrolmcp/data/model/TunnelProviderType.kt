package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Available tunnel provider types for remote access.
 */
enum class TunnelProviderType {
    /** Cloudflare Quick Tunnel — no account needed, random URL per session. */
    CLOUDFLARE,

    /** ngrok tunnel — requires authtoken, supports stable domains. */
    NGROK,
}
