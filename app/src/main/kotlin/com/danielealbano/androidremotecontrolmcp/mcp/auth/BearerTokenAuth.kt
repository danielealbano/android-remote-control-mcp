package com.danielealbano.androidremotecontrolmcp.mcp.auth

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
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
 * @property excludedPaths Paths that skip authentication (e.g., "/health").
 */
class BearerTokenAuthConfig {
    var expectedToken: String = ""
    var excludedPaths: Set<String> = emptySet()
}

/**
 * Ktor Application-level plugin that validates Bearer token authentication.
 *
 * Install this plugin at the Application level to enforce authentication on
 * all incoming requests.
 *
 * Uses [MessageDigest.isEqual] for constant-time token comparison to prevent
 * timing side-channel attacks.
 *
 * Intercepts at [ApplicationCallPipeline.Plugins] and calls
 * [finish][io.ktor.util.pipeline.PipelineContext.finish] on authentication
 * failure to prevent downstream route handlers from executing side effects
 * (e.g., MCP tool dispatch) on unauthenticated requests.
 *
 * Usage:
 * ```kotlin
 * install(BearerTokenAuthPlugin) {
 *     expectedToken = "my-secret-token"
 * }
 * ```
 */
val BearerTokenAuthPlugin =
    createApplicationPlugin(
        name = "BearerTokenAuth",
        createConfiguration = ::BearerTokenAuthConfig,
    ) {
        val expectedToken = pluginConfig.expectedToken
        val excludedPaths = pluginConfig.excludedPaths

        application.intercept(ApplicationCallPipeline.Plugins) {
            // Skip authentication when no bearer token is configured
            if (expectedToken.isEmpty()) {
                return@intercept
            }

            val call = context

            // Skip authentication for excluded paths (e.g., /health)
            val requestPath = call.request.local.uri
            if (excludedPaths.any { requestPath == it }) {
                return@intercept
            }
            val authHeader = call.request.headers["Authorization"]

            if (authHeader == null) {
                val remoteAddr = call.request.local.remoteAddress
                val forwardedFor = call.request.headers["X-Forwarded-For"]
                val addrInfo = if (forwardedFor != null) "$remoteAddr (forwarded-for: $forwardedFor)" else remoteAddr
                Log.w(TAG, "Authentication failed: missing Authorization header from $addrInfo")
                call.respondText(
                    McpJson.encodeToString(
                        AuthErrorResponse.serializer(),
                        AuthErrorResponse(
                            error = "unauthorized",
                            message = "Missing Authorization header. Expected: Bearer <token>",
                        ),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
                return@intercept
            }

            if (!authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                val remoteAddr = call.request.local.remoteAddress
                val forwardedFor = call.request.headers["X-Forwarded-For"]
                val addrInfo = if (forwardedFor != null) "$remoteAddr (forwarded-for: $forwardedFor)" else remoteAddr
                Log.w(TAG, "Authentication failed: malformed Authorization header from $addrInfo")
                call.respondText(
                    McpJson.encodeToString(
                        AuthErrorResponse.serializer(),
                        AuthErrorResponse(
                            error = "unauthorized",
                            message = "Malformed Authorization header. Expected: Bearer <token>",
                        ),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
                return@intercept
            }

            // Use substring instead of removePrefix to handle case-insensitive "Bearer " prefix
            val providedToken = authHeader.substring(BEARER_PREFIX.length).trim()
            val isValid = constantTimeEquals(expectedToken, providedToken)

            if (!isValid) {
                val remoteAddr = call.request.local.remoteAddress
                val forwardedFor = call.request.headers["X-Forwarded-For"]
                val addrInfo = if (forwardedFor != null) "$remoteAddr (forwarded-for: $forwardedFor)" else remoteAddr
                Log.w(TAG, "Authentication failed: invalid token from $addrInfo")
                call.respondText(
                    McpJson.encodeToString(
                        AuthErrorResponse.serializer(),
                        AuthErrorResponse(
                            error = "unauthorized",
                            message = "Invalid bearer token",
                        ),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
                return@intercept
            }
        }
    }

internal fun constantTimeEquals(
    expected: String,
    provided: String,
): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val expectedHash = digest.digest(expected.toByteArray(Charsets.UTF_8))
    val providedHash = digest.digest(provided.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(expectedHash, providedHash)
}

private const val TAG = "MCP:BearerTokenAuth"
private const val BEARER_PREFIX = "Bearer "
