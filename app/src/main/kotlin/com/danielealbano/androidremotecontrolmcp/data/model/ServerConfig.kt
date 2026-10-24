package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Holds the MCP server configuration.
 *
 * All fields have sensible defaults matching the project specification.
 * The bearer token defaults to an empty string and is auto-generated
 * (UUID) on first read by [com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImpl].
 *
 * @property port The server port (1-65535).
 * @property bindingAddress The network binding address.
 * @property bearerToken The bearer token for MCP request authentication.
 * @property autoStartOnBoot Whether to start the MCP server on device boot.
 * @property toolCallIndicatorEnabled Whether to show an on-device overlay during MCP tool calls.
 * @property screenAwakeTimeoutSeconds Idle grace after MCP calls; zero disables keeping the screen awake.
 * @property httpsEnabled Whether HTTPS is enabled (disabled by default).
 * @property certificateSource The source of the HTTPS certificate.
 * @property certificateHostname The hostname for auto-generated certificates.
 * @property tunnelEnabled Whether remote access via tunnel is enabled.
 * @property tunnelProvider The tunnel provider type (free E2E tunnel, Cloudflare or ngrok).
 * @property ngrokAuthtoken The ngrok authtoken (required when using ngrok).
 * @property ngrokDomain The ngrok domain (optional, empty means auto-assigned).
 * @property cloudflareTunnelMode The Cloudflare tunnel mode (Free quick tunnel vs token-based named tunnel).
 * @property cloudflareTunnelToken The Cloudflare tunnel token (required when using token mode).
 * @property arcTunnelBaseAddress Base address of the free E2E tunnel service (`enroll.`, `connect.` and the
 *   tunnel domain `d.` are derived from it).
 * @property arcTunnelCtCheckEnabled Whether the Certificate Transparency check of the tunnel certificate runs.
 * @property arcTunnelEdgeOverride Debug/E2E only: `host:port` the tunnel client dials instead of DNS (empty = none).
 * @property arcTunnelTrustedCaPem Debug/E2E only: PEM CA bundle trusted for the tunnel service (empty = system CAs).
 * @property fileSizeLimitMb File size limit for file operations (in MB).
 * @property allowHttpDownloads Whether HTTP (non-HTTPS) downloads are allowed.
 * @property allowUnverifiedHttpsCerts Whether unverified HTTPS certs are accepted for downloads.
 * @property blockLocalNetworkDownloads Whether `download_from_url` refuses to connect to local network addresses
 *   (loopback, unspecified, private LAN, link-local, CGNAT and IPv6 unique-local, including their
 *   IPv4-mapped / IPv4-compatible IPv6 forms). Off by default.
 * @property downloadTimeoutSeconds Download timeout in seconds.
 * @property deviceSlug Optional device identifier slug for tool name prefix
 *   (letters, digits, underscores; max 20 chars).
 * @property oauthEnabled Whether the self-contained OAuth 2.1 authorization server is enabled
 *   (default true). When enabled, `/mcp` additionally accepts issued OAuth access tokens.
 * @property bearerTokenEnabled Whether static bearer-token authentication is enabled (default true).
 *   Decoupled from the token value: disabling keeps the value; enabling with an empty value
 *   auto-generates a token.
 * @property publicUrlOverride Optional public base URL that pins the host used for OAuth metadata and
 *   share links (empty = auto-detect from the request).
 * @property hideFromRecents Whether to exclude the app from Android's Recent Apps / Overview screen.
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val bindingAddress: BindingAddress = BindingAddress.LOCALHOST,
    val bearerToken: String = "",
    val autoStartOnBoot: Boolean = false,
    val toolCallIndicatorEnabled: Boolean = true,
    val screenAwakeTimeoutSeconds: Int = DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS,
    val httpsEnabled: Boolean = false,
    val certificateSource: CertificateSource = CertificateSource.AUTO_GENERATED,
    val certificateHostname: String = DEFAULT_CERTIFICATE_HOSTNAME,
    val tunnelEnabled: Boolean = false,
    val tunnelProvider: TunnelProviderType = TunnelProviderType.ARC_TUNNEL,
    val ngrokAuthtoken: String = "",
    val ngrokDomain: String = "",
    val cloudflareTunnelMode: CloudflareTunnelMode = CloudflareTunnelMode.FREE,
    val cloudflareTunnelToken: String = "",
    val cloudflareTunnelExtraArgs: String = "",
    val arcTunnelBaseAddress: String = DEFAULT_ARC_TUNNEL_BASE_ADDRESS,
    val arcTunnelCtCheckEnabled: Boolean = true,
    val arcTunnelEdgeOverride: String = "",
    val arcTunnelTrustedCaPem: String = "",
    val fileSizeLimitMb: Int = DEFAULT_FILE_SIZE_LIMIT_MB,
    val allowHttpDownloads: Boolean = false,
    val allowUnverifiedHttpsCerts: Boolean = false,
    val blockLocalNetworkDownloads: Boolean = false,
    val downloadTimeoutSeconds: Int = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
    val deviceSlug: String = "",
    val oauthEnabled: Boolean = true,
    val bearerTokenEnabled: Boolean = true,
    val publicUrlOverride: String = "",
    val toolPermissionsConfig: ToolPermissionsConfig = ToolPermissionsConfig(),
    val privacyModeConfig: PrivacyModeConfig = PrivacyModeConfig(),
    val hideFromRecents: Boolean = false,
) {
    companion object {
        /** Default server port. */
        const val DEFAULT_PORT = 8080

        /** Default base address of the free E2E tunnel service. */
        const val DEFAULT_ARC_TUNNEL_BASE_ADDRESS = "arc-tunnel.io"

        /** Minimum valid port number. */
        const val MIN_PORT = 1

        /** Maximum valid port number. */
        const val MAX_PORT = 65535

        /** Default hostname for auto-generated certificates. */
        const val DEFAULT_CERTIFICATE_HOSTNAME = "android-mcp.local"

        /** Default file size limit in megabytes. */
        const val DEFAULT_FILE_SIZE_LIMIT_MB = 50

        /** Minimum file size limit in megabytes. */
        const val MIN_FILE_SIZE_LIMIT_MB = 1

        /** Maximum file size limit in megabytes. */
        const val MAX_FILE_SIZE_LIMIT_MB = 500

        /** Default download timeout in seconds. */
        const val DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 60

        /** Minimum download timeout in seconds. */
        const val MIN_DOWNLOAD_TIMEOUT_SECONDS = 10

        /** Maximum download timeout in seconds. */
        const val MAX_DOWNLOAD_TIMEOUT_SECONDS = 300

        /** Disabled by default; existing screen-lock behavior is preserved on upgrade. */
        const val DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS = 0

        /** Minimum nonzero idle grace after the final MCP action, in seconds. */
        const val MIN_SCREEN_AWAKE_TIMEOUT_SECONDS = 60

        /** Maximum idle grace, in seconds (48 hours). */
        const val MAX_SCREEN_AWAKE_TIMEOUT_SECONDS = 172800

        /** Shared range policy for persisted settings, input validation and runtime configuration. */
        fun isValidScreenAwakeTimeout(seconds: Int): Boolean =
            seconds == 0 || seconds in MIN_SCREEN_AWAKE_TIMEOUT_SECONDS..MAX_SCREEN_AWAKE_TIMEOUT_SECONDS

        /** Maximum length for device slug. */
        const val MAX_DEVICE_SLUG_LENGTH = 20

        /** Pattern for valid device slug characters (letters, digits, underscores). */
        val DEVICE_SLUG_PATTERN = Regex("^[a-zA-Z0-9_]*$")
    }
}
