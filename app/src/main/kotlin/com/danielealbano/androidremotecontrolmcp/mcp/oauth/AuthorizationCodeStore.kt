package com.danielealbano.androidremotecontrolmcp.mcp.oauth

/** A pending authorization code (RFC 6749 §4.1), single-use and short-lived. */
data class AuthorizationCode(
    val code: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val scope: String,
    val expiresAtMs: Long,
    /** The CIMD client's document read at `/authorize`; null for a DCR client. */
    val metadataDocument: ClientMetadataDocument? = null,
)

/** The minted-code fields supplied when creating an authorization code. */
data class AuthorizationCodeRequest(
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val scope: String,
    /** The CIMD client's document read at `/authorize`; null for a DCR client. */
    val metadataDocument: ClientMetadataDocument? = null,
)

/**
 * Single-use, TTL-bounded store of authorization codes minted at `/authorize` approval and redeemed
 * once at `/token`.
 */
interface AuthorizationCodeStore {
    /** Creates and stores a code, returning the opaque code value. */
    suspend fun create(
        request: AuthorizationCodeRequest,
        nowMs: Long,
    ): String

    /** Consumes the code (removed on read); returns null if missing or expired. */
    suspend fun consume(
        code: String,
        nowMs: Long,
    ): AuthorizationCode?
}
