package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.annotation.StringRes
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
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
     * A tunnel is enabled. [mcpUrl] is the public MCP URL, null until the tunnel is connected. The services log in
     * with OAuth, so the buttons work only with [mcpUrl] and while [oauthEnabled].
     */
    data class TunnelEnabled(
        val mcpUrl: String?,
        val oauthEnabled: Boolean,
    ) : RegisterCardState {
        val canRegister: Boolean get() = mcpUrl != null && oauthEnabled

        /** Why the buttons are disabled: the public URL is not known yet, OAuth is off. */
        override val hintsRes: List<Int>
            get() =
                listOfNotNull(
                    R.string.register_card_no_url.takeIf { mcpUrl == null },
                    R.string.register_card_oauth_off.takeIf { !oauthEnabled },
                )
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

    /** The page [target]'s button opens: Claude's dialog filled in with [connectorName] and [mcpUrl], else its page. */
    fun link(
        target: RegisterTarget,
        connectorName: String,
        mcpUrl: String,
    ): String =
        when (target) {
            RegisterTarget.CLAUDE -> claudeUrl(connectorName, mcpUrl)
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
     * The card for [config] and [tunnelStatus]. The MCP URL is the first valid endpoint of a connected tunnel, or the
     * public URL override when one is set: the OAuth metadata names the override, so a service given another URL
     * could not log in.
     */
    fun cardState(
        config: ServerConfig,
        tunnelStatus: TunnelStatus,
    ): RegisterCardState {
        if (!config.tunnelEnabled) return RegisterCardState.NoTunnel
        val endpoint = (tunnelStatus as? TunnelStatus.Connected)?.endpoints?.firstOrNull { it.valid }?.url
        val baseUrl = endpoint?.let { config.publicUrlOverride.trim().ifEmpty { it } }
        return RegisterCardState.TunnelEnabled(
            mcpUrl = baseUrl?.let { canonicalResource(normalizeBaseUrl(it)) },
            oauthEnabled = config.oauthEnabled,
        )
    }

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
