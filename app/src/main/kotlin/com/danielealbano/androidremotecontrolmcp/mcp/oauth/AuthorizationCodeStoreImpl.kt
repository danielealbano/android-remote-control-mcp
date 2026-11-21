package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.inject.Inject

/** In-memory, thread-safe [AuthorizationCodeStore]. Codes are single-use and expire after 60s. */
class AuthorizationCodeStoreImpl
    @Inject
    constructor() : AuthorizationCodeStore {
        private val mutex = Mutex()
        private val codes = LinkedHashMap<String, AuthorizationCode>()
        private val secureRandom = SecureRandom()

        override suspend fun create(
            request: AuthorizationCodeRequest,
            nowMs: Long,
        ): String =
            mutex.withLock {
                codes.values.removeAll { it.expiresAtMs <= nowMs }
                val code = "code-${randomHex()}"
                codes[code] =
                    AuthorizationCode(
                        code = code,
                        clientId = request.clientId,
                        redirectUri = request.redirectUri,
                        codeChallenge = request.codeChallenge,
                        resource = request.resource,
                        scope = request.scope,
                        expiresAtMs = nowMs + OAuthPolicy.AUTH_CODE_TTL_MS,
                        metadataDocument = request.metadataDocument,
                    )
                code
            }

        override suspend fun consume(
            code: String,
            nowMs: Long,
        ): AuthorizationCode? =
            mutex.withLock {
                val entry = codes.remove(code) ?: return@withLock null
                if (entry.expiresAtMs <= nowMs) null else entry
            }

        private fun randomHex(): String {
            val bytes = ByteArray(CODE_BYTES).also { secureRandom.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private companion object {
            const val CODE_BYTES = 16
        }
    }
