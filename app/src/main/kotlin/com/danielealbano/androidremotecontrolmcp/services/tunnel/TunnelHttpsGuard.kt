package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType

/** Cloudflare/ngrok proxy an http://localhost origin and cannot run with HTTPS; the free E2E tunnel can. */
internal fun tunnelAllowedWithHttps(config: ServerConfig): Boolean =
    !config.httpsEnabled || config.tunnelProvider == TunnelProviderType.ARC_TUNNEL
