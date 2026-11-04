package com.danielealbano.androidremotecontrolmcp.mcp.auth

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.canonicalResource
import com.danielealbano.androidremotecontrolmcp.mcp.clientIp
import com.danielealbano.androidremotecontrolmcp.mcp.deriveBaseUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelinePhase
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
 * Configuration for the [McpAuthPlugin].
 *
 * Authentication is required iff `bearerTokenEnabled || oauthEnabled`. With both disabled the server is
 * OPEN (explicit, allowed). A request is authorized when the static token matches OR a valid OAuth
 * access token is presented (dual-accept).
 *
 * @property bearerTokenEnabled Whether static bearer-token authentication is active.
 * @property expectedToken The static bearer token (empty + enabled = fail closed; no bearer path).
 * @property oauthEnabled Whether OAuth access tokens are accepted.
 * @property validateOAuthToken Validates an OAuth access token against the canonical resource (null when
 *   OAuth is unavailable).
 * @property baseUrlOf Derives the effective public base URL for the discovery header.
 * @property excludedPaths Paths that skip authentication via EXACT match (e.g., "/health").
 * @property excludedPathPrefixes Paths whose (normalized) request path STARTS WITH any of these prefixes
 *   skip authentication. Use only for routes where the secret is in the path itself.
 * @property onAuthFailure Invoked with the remote-address info on every 401 (fail-closed branch only).
 */
class McpAuthConfig {
    var bearerTokenEnabled: Boolean = true
    var expectedToken: String = ""
    var oauthEnabled: Boolean = false
    var validateOAuthToken: (suspend (token: String, canonicalResource: String) -> Boolean)? = null
    var baseUrlOf: (ApplicationCall) -> String = { deriveBaseUrl(it) }
    var excludedPaths: Set<String> = emptySet()
    var excludedPathPrefixes: Set<String> = emptySet()
    var onAuthFailure: ((remoteInfo: String) -> Unit)? = null
}

/**
 * Ktor Application-level plugin enforcing combined MCP authentication: static bearer token OR issued
 * OAuth access token (dual-accept). Open when both methods are disabled.
 *
 * Uses [MessageDigest.isEqual] for constant-time token comparison. Intercepts in a custom phase
 * inserted just before [ApplicationCallPipeline.Call] and calls
 * [finish][io.ktor.util.pipeline.PipelineContext.finish] on failure so downstream handlers do not run
 * on unauthenticated requests. A 401 carries the OAuth discovery
 * `WWW-Authenticate: Bearer resource_metadata="…"` header ONLY when OAuth is enabled.
 *
 * The phase MUST run AFTER Ktor's CORS plugin, which since Ktor 3.5 registers in the (internal)
 * `Validators` phase (via `onCallValidators`) — i.e. AFTER the public `Plugins` phase. Intercepting
 * `Plugins` directly would run auth BEFORE CORS, so a token-less browser preflight `OPTIONS` would be
 * failed closed (401) instead of answered, and a token-less cross-origin request's 401 would miss the
 * `Access-Control-Allow-Origin` header. Inserting our phase before the public `Call` phase places it
 * after `Validators`, so CORS answers preflights and decorates error responses before auth runs.
 */
val McpAuthPlugin =
    createApplicationPlugin(
        name = "McpAuth",
        createConfiguration = ::McpAuthConfig,
    ) {
        val bearerTokenEnabled = pluginConfig.bearerTokenEnabled
        val expectedToken = pluginConfig.expectedToken
        val oauthEnabled = pluginConfig.oauthEnabled
        val validateOAuthToken = pluginConfig.validateOAuthToken
        val baseUrlOf = pluginConfig.baseUrlOf
        val excludedPaths = pluginConfig.excludedPaths
        val excludedPathPrefixes = pluginConfig.excludedPathPrefixes
        val onAuthFailure = pluginConfig.onAuthFailure

        // Insert a dedicated auth phase AFTER the (internal) Validators phase where the CORS plugin
        // lives since Ktor 3.5, so CORS answers preflights and decorates responses before auth runs.
        // `Call` is public and sits immediately after `Validators`, so inserting before it is safe.
        val authPhase = PipelinePhase("McpAuth")
        application.insertPhaseBefore(ApplicationCallPipeline.Call, authPhase)

        application.intercept(authPhase) {
            // Open server: neither method enabled.
            if (!bearerTokenEnabled && !oauthEnabled) {
                return@intercept
            }

            val call = context
            // CORS preflight (OPTIONS) is answered by the CORS plugin in the earlier Validators phase
            // and carries no credentials; never fail it closed. The MCP transport exposes no
            // authenticated OPTIONS endpoint, so skipping the method entirely is safe.
            if (call.request.httpMethod == HttpMethod.Options) {
                return@intercept
            }
            val requestPath = call.request.path()
            if (excludedPaths.any { requestPath == it }) {
                return@intercept
            }
            if (excludedPathPrefixes.any { requestPath.startsWith(it) }) {
                return@intercept
            }

            // Extract the Bearer token; an absent/malformed header yields an ABSENT token (no early 401).
            val authHeader = call.request.headers["Authorization"]
            val providedToken =
                if (authHeader != null && authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                    authHeader.substring(BEARER_PREFIX.length).trim()
                } else {
                    ""
                }

            // Static bearer path.
            if (bearerTokenEnabled && expectedToken.isNotEmpty() && constantTimeEquals(expectedToken, providedToken)) {
                return@intercept
            }

            // OAuth access-token path.
            if (oauthEnabled && validateOAuthToken != null &&
                validateOAuthToken(providedToken, canonicalResource(baseUrlOf(call)))
            ) {
                return@intercept
            }

            // Fail closed.
            val addrInfo = call.clientIp() ?: "unknown"
            Log.w(TAG, "Authentication failed from $addrInfo")
            onAuthFailure?.invoke(addrInfo)
            if (oauthEnabled) {
                call.response.header(
                    "WWW-Authenticate",
                    "Bearer resource_metadata=\"${baseUrlOf(call)}/.well-known/oauth-protected-resource/mcp\"",
                )
            }
            call.respondText(
                McpJson.encodeToString(
                    AuthErrorResponse.serializer(),
                    AuthErrorResponse(
                        error = "unauthorized",
                        message = "Authentication required",
                    ),
                ),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            finish()
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

private const val TAG = "MCP:McpAuth"
private const val BEARER_PREFIX = "Bearer "
