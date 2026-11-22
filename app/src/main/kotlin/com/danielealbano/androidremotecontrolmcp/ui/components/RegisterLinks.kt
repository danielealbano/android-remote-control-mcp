package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.annotation.StringRes
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.mcp.canonicalResource
import com.danielealbano.androidremotecontrolmcp.mcp.normalizeBaseUrl
import java.net.URLEncoder

/**
 * A service the "Register with" card sends this server's MCP URL to. [stepsRes] says where to paste the URL when the
 * service's form cannot be filled in from a link, so the URL is copied first ([copiesUrl]).
 */
enum class RegisterTarget(
    @StringRes val labelRes: Int,
    @StringRes val stepsRes: Int?,
) {
    CLAUDE(R.string.register_target_claude, null),
    CHATGPT(R.string.register_target_chatgpt, R.string.register_chatgpt_steps),
    GEMINI(R.string.register_target_gemini, R.string.register_gemini_steps),
    ;

    val copiesUrl: Boolean get() = stepsRes != null
}

/** What the home-screen "Register with" card shows. */
sealed interface RegisterCardState {
    /** The card's message or hints, one per line. */
    val hintsRes: List<Int>

    /** No tunnel is enabled: only a message that one is needed first. */
    data object NoTunnel : RegisterCardState {
        override val hintsRes: List<Int> get() = listOf(R.string.register_card_no_tunnel)
    }

    /**
     * A tunnel is enabled. The services reach the phone through [url] and log in with OAuth, so the buttons work only
     * with a known URL, while [oauthEnabled], and once the running server uses the saved settings ([restartNeeded]
     * false).
     */
    data class TunnelEnabled(
        val url: TunnelUrl,
        val oauthEnabled: Boolean,
        val restartNeeded: Boolean,
    ) : RegisterCardState {
        val mcpUrl: String? get() = (url as? TunnelUrl.Known)?.mcpUrl

        val canRegister: Boolean get() = url is TunnelUrl.Known && oauthEnabled && !restartNeeded

        /** Why the buttons are disabled: no URL yet (and why), OAuth is off, the server must restart. */
        override val hintsRes: List<Int>
            get() =
                listOfNotNull(
                    (url as? TunnelUrl.Missing)?.hintRes,
                    R.string.register_card_oauth_off.takeIf { !oauthEnabled },
                    R.string.register_card_restart.takeIf { restartNeeded && oauthEnabled },
                )
    }
}

/** The public MCP URL the card registers, or why there is none yet. */
sealed interface TunnelUrl {
    data class Known(
        val mcpUrl: String,
    ) : TunnelUrl

    /** No public URL; [hintRes] says why. */
    enum class Missing(
        @StringRes val hintRes: Int,
    ) : TunnelUrl {
        /** The server (and so the tunnel) is not started. */
        SERVER_STOPPED(R.string.register_card_no_url),

        /** The server runs and the tunnel is coming up. */
        WAITING(R.string.register_card_waiting_url),

        /** The tunnel failed, or is connected with no usable endpoint (a Cloudflare tunnel without a route). */
        TUNNEL_FAILED(R.string.register_card_tunnel_failed),
    }
}

/** The links, names and state of the "Register with" card. */
object RegisterLinks {
    /** ChatGPT's plugins page, where "+" then "Add custom MCP server" adds one (no link fills the form in). */
    const val CHATGPT_URL = "https://chatgpt.com/plugins"

    /** Gemini's connectors page, where "+" adds a custom one (no link fills the form in). */
    const val GEMINI_URL = "https://gemini.google.com/customize/connectors"

    /** Claude's add-custom-connector dialog; `connectorName` and `connectorUrl` fill it in. */
    private const val CLAUDE_ADD_URL = "https://claude.ai/customize/connectors?modal=add-custom-connector"

    private const val APP_NAME = "Android Remote Control"

    /**
     * The page [target]'s button opens: Claude's dialog filled in with [mcpUrl] and the [connectorName] (asked for only
     * then), else the service's page.
     */
    fun link(
        target: RegisterTarget,
        mcpUrl: String,
        connectorName: () -> String,
    ): String =
        when (target) {
            RegisterTarget.CLAUDE -> claudeUrl(connectorName(), mcpUrl)
            RegisterTarget.CHATGPT -> CHATGPT_URL
            RegisterTarget.GEMINI -> GEMINI_URL
        }

    /** The message once [target]'s page is [opened]: where to paste the copied URL, if needed; else that no app can. */
    fun messageAfterOpen(
        target: RegisterTarget,
        opened: Boolean,
    ): Int? = if (opened) target.stepsRes else R.string.register_no_app

    /** Claude's add-custom-connector dialog with [name] and [mcpUrl] filled in. */
    fun claudeUrl(
        name: String,
        mcpUrl: String,
    ): String = "$CLAUDE_ADD_URL&connectorName=${encode(name)}&connectorUrl=${encode(mcpUrl)}"

    /** The connector name: "<slug> - Android Remote Control" when [deviceSlug] is set, else "<phone name> - ...". */
    fun connectorName(
        deviceSlug: String,
        phoneName: String,
    ): String = "${deviceSlug.trim().ifEmpty { phoneName }} - $APP_NAME"

    /** The phone's name: the device name the user sees in the system settings, else its manufacturer and model. */
    fun phoneName(
        deviceName: String?,
        manufacturer: String,
        model: String,
    ): String =
        deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"

    /**
     * The card for the saved [config], [tunnelStatus] and [serverStatus]. The MCP URL is the first valid endpoint of a
     * connected tunnel, or the public URL override when one is set: the OAuth metadata names the override, so a
     * service given another URL could not log in. A running server keeps the OAuth toggle and the override it started
     * with, so a change of either needs a restart first.
     */
    fun cardState(
        config: ServerConfig,
        tunnelStatus: TunnelStatus,
        serverStatus: ServerStatus,
    ): RegisterCardState {
        if (!config.tunnelEnabled) return RegisterCardState.NoTunnel
        val running = serverStatus as? ServerStatus.Running
        val restartNeeded =
            running != null &&
                (
                    running.oauthEnabled != config.oauthEnabled ||
                        overrideBase(running.publicUrlOverride) != overrideBase(config.publicUrlOverride)
                )
        val url = tunnelUrl(config, tunnelStatus, serverStatus)
        return RegisterCardState.TunnelEnabled(url, config.oauthEnabled, restartNeeded)
    }

    private fun tunnelUrl(
        config: ServerConfig,
        tunnelStatus: TunnelStatus,
        serverStatus: ServerStatus,
    ): TunnelUrl {
        val connected = tunnelStatus as? TunnelStatus.Connected
        val endpoint = connected?.endpoints?.firstOrNull { it.valid }?.url
        val started = serverStatus is ServerStatus.Starting || serverStatus is ServerStatus.Running
        return when {
            endpoint != null -> {
                val baseUrl = overrideBase(config.publicUrlOverride) ?: normalizeBaseUrl(endpoint)
                TunnelUrl.Known(canonicalResource(baseUrl))
            }

            !started -> {
                TunnelUrl.Missing.SERVER_STOPPED
            }

            tunnelStatus is TunnelStatus.Error || connected != null -> {
                TunnelUrl.Missing.TUNNEL_FAILED
            }

            else -> {
                TunnelUrl.Missing.WAITING
            }
        }
    }

    /** The normalized base URL of a public URL [override], or null when none is set. */
    private fun overrideBase(override: String): String? = override.trim().ifEmpty { null }?.let(::normalizeBaseUrl)

    /**
     * The browser to open the links in: [defaultPackage] (what Android resolves a web link to) when it is one of
     * [browsers], else null. With no default browser set, Android resolves to its app chooser, which is not a browser.
     */
    fun browserPackage(
        defaultPackage: String?,
        browsers: Collection<String>,
    ): String? = defaultPackage?.takeIf { it in browsers }

    /** Percent-encoding for a query value, with `%20` (not `+`) for a space. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
