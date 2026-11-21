package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * HS256 implementation of [JwtTokenService]. The [Algorithm] is memoized once per process (the signing
 * secret is read from [SettingsRepository] only on first use), so neither issue nor the hot-path verify
 * touches DataStore per request.
 */
class JwtTokenServiceImpl
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : JwtTokenService {
        @Volatile
        private var algorithm: Algorithm? = null
        private val algorithmMutex = Mutex()

        private suspend fun algorithm(): Algorithm {
            algorithm?.let { return it }
            return algorithmMutex.withLock {
                algorithm ?: Algorithm.HMAC256(settingsRepository.getOrCreateJwtSigningSecret()).also {
                    algorithm = it
                }
            }
        }

        override suspend fun issueAccessToken(
            clientId: String,
            resource: String,
            connectionId: String?,
        ): String =
            sign(
                TokenSubject(clientId, connectionId),
                resource = resource,
                type = TYPE_ACCESS,
                jti = UUID.randomUUID().toString(),
                ttlSeconds = OAuthPolicy.ACCESS_TOKEN_TTL_SECONDS,
            )

        override suspend fun issueRefreshToken(
            clientId: String,
            jti: String,
            resource: String,
            connectionId: String?,
        ): String =
            sign(
                TokenSubject(clientId, connectionId),
                resource = resource,
                type = TYPE_REFRESH,
                jti = jti,
                ttlSeconds = OAuthPolicy.REFRESH_TOKEN_TTL_SECONDS,
            )

        /** Who a token is for: the client, and the connection for a CIMD client. */
        private class TokenSubject(
            val clientId: String,
            val connectionId: String?,
        )

        private suspend fun sign(
            subject: TokenSubject,
            resource: String,
            type: String,
            jti: String,
            ttlSeconds: Long,
        ): String {
            val now = System.currentTimeMillis()
            return JWT
                .create()
                .withIssuer(ISS)
                .withSubject(subject.clientId)
                .withClaim(CLAIM_CLIENT_ID, subject.clientId)
                .apply { subject.connectionId?.let { withClaim(CLAIM_CONNECTION_ID, it) } }
                .withClaim(CLAIM_TYPE, type)
                .withAudience(resource)
                .withJWTId(jti)
                .withIssuedAt(Date(now))
                .withExpiresAt(Date(now + ttlSeconds * MILLIS_PER_SECOND))
                .sign(algorithm())
        }

        override suspend fun verifyAccessToken(token: String): AccessTokenClaims? {
            val decoded = verify(token, TYPE_ACCESS) ?: return null
            val clientId = decoded.getClaim(CLAIM_CLIENT_ID).asString()
            val audience = decoded.audience?.firstOrNull()
            return if (clientId != null && audience != null) {
                AccessTokenClaims(clientId, audience, decoded.getClaim(CLAIM_CONNECTION_ID).asString())
            } else {
                null
            }
        }

        override suspend fun verifyRefreshToken(token: String): RefreshTokenClaims? {
            val decoded = verify(token, TYPE_REFRESH) ?: return null
            val clientId = decoded.getClaim(CLAIM_CLIENT_ID).asString()
            val jti = decoded.id
            return if (clientId != null && jti != null) {
                RefreshTokenClaims(clientId, jti, decoded.getClaim(CLAIM_CONNECTION_ID).asString())
            } else {
                null
            }
        }

        private suspend fun verify(
            token: String,
            expectedType: String,
        ): DecodedJWT? =
            runCatching {
                JWT
                    .require(algorithm())
                    .withIssuer(ISS)
                    .withClaim(CLAIM_TYPE, expectedType)
                    .build()
                    .verify(token)
            }.getOrNull()

        private companion object {
            const val ISS = "android-remote-control-mcp"
            const val CLAIM_CLIENT_ID = "client_id"
            const val CLAIM_CONNECTION_ID = "connection_id"
            const val CLAIM_TYPE = "typ"
            const val TYPE_ACCESS = "access"
            const val TYPE_REFRESH = "refresh"
            const val MILLIS_PER_SECOND = 1000L
        }
    }
