package com.danielealbano.androidremotecontrolmcp.mcp.oauth

/** Claims recovered from a verified OAuth access token; [connectionId] only for a CIMD client's connection. */
data class AccessTokenClaims(
    val clientId: String,
    val audience: String,
    val connectionId: String? = null,
)

/** Claims recovered from a verified OAuth refresh token; [connectionId] only for a CIMD client's connection. */
data class RefreshTokenClaims(
    val clientId: String,
    val jti: String,
    val connectionId: String? = null,
)

/**
 * Issues and verifies HS256 OAuth tokens for the self-contained authorization server. The signing
 * secret is device-held (never published as a JWKS), so the same service both mints and validates.
 * Both access and refresh tokens carry `aud = resource` and a `client_id` claim, and for a CIMD client the id of the
 * connection ([OAuthConnection]) they belong to.
 */
interface JwtTokenService {
    suspend fun issueAccessToken(
        clientId: String,
        resource: String,
        connectionId: String? = null,
    ): String

    suspend fun issueRefreshToken(
        clientId: String,
        jti: String,
        resource: String,
        connectionId: String? = null,
    ): String

    suspend fun verifyAccessToken(token: String): AccessTokenClaims?

    suspend fun verifyRefreshToken(token: String): RefreshTokenClaims?
}
