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
 * @property httpsEnabled Whether HTTPS is enabled (disabled by default).
 * @property certificateSource The source of the HTTPS certificate.
 * @property certificateHostname The hostname for auto-generated certificates.
 * @property tunnelEnabled Whether remote access via tunnel is enabled.
 * @property tunnelProvider The tunnel provider type (Cloudflare or ngrok).
 * @property ngrokAuthtoken The ngrok authtoken (required when using ngrok).
 * @property ngrokDomain The ngrok domain (optional, empty means auto-assigned).
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val bindingAddress: BindingAddress = BindingAddress.LOCALHOST,
    val bearerToken: String = "",
    val autoStartOnBoot: Boolean = false,
    val httpsEnabled: Boolean = false,
    val certificateSource: CertificateSource = CertificateSource.AUTO_GENERATED,
    val certificateHostname: String = DEFAULT_CERTIFICATE_HOSTNAME,
    val tunnelEnabled: Boolean = false,
    val tunnelProvider: TunnelProviderType = TunnelProviderType.CLOUDFLARE,
    val ngrokAuthtoken: String = "",
    val ngrokDomain: String = "",
) {
    companion object {
        /** Default server port. */
        const val DEFAULT_PORT = 8080

        /** Minimum valid port number. */
        const val MIN_PORT = 1

        /** Maximum valid port number. */
        const val MAX_PORT = 65535

        /** Default hostname for auto-generated certificates. */
        const val DEFAULT_CERTIFICATE_HOSTNAME = "android-mcp.local"
    }
}
