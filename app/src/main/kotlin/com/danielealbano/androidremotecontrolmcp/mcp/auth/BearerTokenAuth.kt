package com.danielealbano.androidremotecontrolmcp.mcp.auth

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Response body returned for authentication failures.
 */
@Serializable
data class AuthErrorResponse(
    val error: String,
    val message: String,
)

/**
 * Configuration for the [BearerTokenAuthPlugin].
 *
 * @property expectedToken The bearer token that clients must present.
 */
class BearerTokenAuthConfig {
    var expectedToken: String = ""
}

/**
 * Ktor route-scoped plugin that validates Bearer token authentication.
 *
 * Install this plugin on route groups that require authentication (e.g., `/mcp`).
 * Routes outside the installation scope (e.g., `/health`) are not affected.
 *
 * Uses [MessageDigest.isEqual] for constant-time token comparison to prevent
 * timing side-channel attacks.
 *
 * Usage:
 * ```kotlin
 * route("/mcp") {
 *     install(BearerTokenAuthPlugin) {
 *         expectedToken = "my-secret-token"
 *     }
 *     // ... protected routes
 * }
 * ```
 */
val BearerTokenAuthPlugin =
    createRouteScopedPlugin(
        name = "BearerTokenAuth",
        createConfiguration = ::BearerTokenAuthConfig,
    ) {
        val expectedToken = pluginConfig.expectedToken

        on(AuthenticationHook) { call ->
            val authHeader = call.request.headers["Authorization"]

            if (authHeader == null) {
                val remoteAddr = call.request.local.remoteAddress
                Log.w(TAG, "Authentication failed: missing Authorization header from $remoteAddr")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(
                        error = "unauthorized",
                        message = "Missing Authorization header. Expected: Bearer <token>",
                    ),
                )
                return@on
            }

            if (!authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                val remoteAddr = call.request.local.remoteAddress
                Log.w(TAG, "Authentication failed: malformed Authorization header from $remoteAddr")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(
                        error = "unauthorized",
                        message = "Malformed Authorization header. Expected: Bearer <token>",
                    ),
                )
                return@on
            }

            // Use substring instead of removePrefix to handle case-insensitive "Bearer " prefix
            val providedToken = authHeader.substring(BEARER_PREFIX.length).trim()
            val isValid = constantTimeEquals(expectedToken, providedToken)

            if (!isValid) {
                Log.w(TAG, "Authentication failed: invalid token from ${call.request.local.remoteAddress}")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(
                        error = "unauthorized",
                        message = "Invalid bearer token",
                    ),
                )
                return@on
            }
        }
    }

private object AuthenticationHook : Hook<suspend (io.ktor.server.application.ApplicationCall) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (io.ktor.server.application.ApplicationCall) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) {
            handler(call)
            // If the auth handler sent a response (e.g., 401), stop the pipeline
            // to prevent route handlers from executing after authentication failure
            if (call.response.status() != null) {
                finish()
            }
        }
    }
}

internal fun constantTimeEquals(
    expected: String,
    provided: String,
): Boolean {
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    val providedBytes = provided.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(expectedBytes, providedBytes)
}

private const val TAG = "MCP:BearerTokenAuth"
private const val BEARER_PREFIX = "Bearer "
