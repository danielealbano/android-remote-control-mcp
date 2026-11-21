package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlin.io.encoding.Base64

/**
 * Embeds a client's logo in the consent page. The phone downloads it (through the guarded [OAuthHttpFetcher]) and
 * serves it as a `data:` URI, so the browser never contacts the logo's server: the client cannot swap the image or see
 * who opens the page (draft-ietf-oauth-client-id-metadata-document section 10.9). Only PNG, JPEG, GIF and WebP, told
 * by their first bytes and never by the server's content type, are embedded.
 */
object ConsentLogo {
    /** The logo at [logoUri] as a `data:` URI, or null when there is none or it is not one of the image types. */
    suspend fun embed(
        fetcher: OAuthHttpFetcher,
        logoUri: String?,
    ): String? {
        val bytes = logoUri?.let { fetcher.get(it, ACCEPT, OAuthPolicy.MAX_LOGO_BYTES)?.bytes }
        val type = bytes?.let(::imageType)
        return if (bytes != null && type != null) "data:$type;base64,${Base64.encode(bytes)}" else null
    }

    /** The image media type [bytes] start with, or null. */
    fun imageType(bytes: ByteArray): String? =
        when {
            bytes.startsWith(PNG) -> "image/png"
            bytes.startsWith(JPEG) -> "image/jpeg"
            bytes.startsWith(GIF87A) || bytes.startsWith(GIF89A) -> "image/gif"
            bytes.startsWith(RIFF) && bytes.startsWith(WEBP, WEBP_OFFSET) -> "image/webp"
            else -> null
        }

    private fun ByteArray.startsWith(
        prefix: ByteArray,
        offset: Int = 0,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private const val ACCEPT = "image/png, image/jpeg, image/gif, image/webp"
    private const val WEBP_OFFSET = 8
    private val PNG = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = bytesOf(0xFF, 0xD8, 0xFF)
    private val GIF87A = "GIF87a".encodeToByteArray()
    private val GIF89A = "GIF89a".encodeToByteArray()
    private val RIFF = "RIFF".encodeToByteArray()
    private val WEBP = "WEBP".encodeToByteArray()
}
