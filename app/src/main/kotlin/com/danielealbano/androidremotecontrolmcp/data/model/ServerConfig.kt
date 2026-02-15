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
 * @property fileSizeLimitMb File size limit for file operations (in MB).
 * @property allowHttpDownloads Whether HTTP (non-HTTPS) downloads are allowed.
 * @property allowUnverifiedHttpsCerts Whether unverified HTTPS certs are accepted for downloads.
 * @property downloadTimeoutSeconds Download timeout in seconds.
 * @property deviceSlug Optional device identifier slug for tool name prefix (letters, digits, underscores; max 20 chars).
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
    val fileSizeLimitMb: Int = DEFAULT_FILE_SIZE_LIMIT_MB,
    val allowHttpDownloads: Boolean = false,
    val allowUnverifiedHttpsCerts: Boolean = false,
    val downloadTimeoutSeconds: Int = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
    val deviceSlug: String = "",
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

        /** Maximum length for device slug. */
        const val MAX_DEVICE_SLUG_LENGTH = 20

        /** Pattern for valid device slug characters (letters, digits, underscores). */
        val DEVICE_SLUG_PATTERN = Regex("^[a-zA-Z0-9_]*$")
    }
}
