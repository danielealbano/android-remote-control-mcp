package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents the source of the HTTPS certificate used by the MCP server.
 *
 * HTTPS is optional (disabled by default). When enabled, the user
 * chooses between an auto-generated self-signed certificate or a custom uploaded certificate.
 */
enum class CertificateSource {
    /** Auto-generated self-signed certificate with configurable hostname. */
    AUTO_GENERATED,

    /** Custom certificate uploaded by the user (.p12 / .pfx file). */
    CUSTOM,
    ;

    companion object {
        /** Returns the [CertificateSource] matching the given [name], or [AUTO_GENERATED] if not found. */
        fun fromName(name: String): CertificateSource = entries.firstOrNull { it.name == name } ?: AUTO_GENERATED
    }
}
