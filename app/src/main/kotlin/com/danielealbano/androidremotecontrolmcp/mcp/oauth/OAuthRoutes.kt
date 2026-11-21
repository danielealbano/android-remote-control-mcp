package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.geo.GeoLocation
import com.danielealbano.androidremotecontrolmcp.mcp.canonicalResource
import com.danielealbano.androidremotecontrolmcp.mcp.clientIp
import com.danielealbano.androidremotecontrolmcp.mcp.effectiveBaseUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Registers the OAuth endpoints (all UNAUTHENTICATED — the combined-auth plugin excludes them). Mount
 * only when OAuth is enabled.
 */
fun Route.installOAuthRoutes(deps: OAuthRouteDeps) {
    val pendingAuthorize = PendingAuthorizeStore()

    get("/.well-known/oauth-protected-resource") { call.respondProtectedResourceMetadata(deps) }
    get("/.well-known/oauth-protected-resource/{tail...}") { call.respondProtectedResourceMetadata(deps) }
    get("/.well-known/oauth-authorization-server") { call.respondAuthorizationServerMetadata(deps) }
    get("/.well-known/oauth-authorization-server/{tail...}") { call.respondAuthorizationServerMetadata(deps) }
    get("/.well-known/openid-configuration") { call.respondAuthorizationServerMetadata(deps) }

    post("/register") { call.handleRegister(deps) }
    get("/authorize") { call.handleAuthorize(deps, pendingAuthorize) }
    get("/authorize/status") { call.handleAuthorizeStatus(deps, pendingAuthorize) }
    post("/token") { call.handleToken(deps) }
}

private suspend fun ApplicationCall.respondProtectedResourceMetadata(deps: OAuthRouteDeps) {
    respondText(
        OAuthMetadata.protectedResourceMetadata(effectiveBaseUrl(this, deps.publicUrlOverride)),
        ContentType.Application.Json,
    )
}

private suspend fun ApplicationCall.respondAuthorizationServerMetadata(deps: OAuthRouteDeps) {
    respondText(
        OAuthMetadata.authorizationServerMetadata(effectiveBaseUrl(this, deps.publicUrlOverride)),
        ContentType.Application.Json,
    )
}

private suspend fun ApplicationCall.handleRegister(deps: OAuthRouteDeps) {
    val body = runCatching { Json.parseToJsonElement(receiveText()).jsonObject }.getOrNull()
    val redirectUris = body?.stringArray("redirect_uris").orEmpty()
    if (body == null) {
        respondOAuthError(HttpStatusCode.BadRequest, "invalid_request")
        return
    }
    val rejectedRedirectUris = redirectUris.filterNot(OAuthPolicy::isAllowedRedirectUri)
    if (redirectUris.isEmpty() || rejectedRedirectUris.isNotEmpty()) {
        if (rejectedRedirectUris.isNotEmpty()) {
            deps.serverLog.log(
                ServerLogEntry.Type.OAUTH,
                "OAuth client registration rejected redirect URI(s): ${rejectedRedirectUris.joinToString()}",
            )
        }
        respondOAuthError(HttpStatusCode.BadRequest, "invalid_redirect_uri")
        return
    }
    val client =
        deps.clientRepository.register(
            clientName = body.stringField("client_name"),
            redirectUris = redirectUris,
            applicationType = body.stringField("application_type"),
            logoUri = body.stringField("logo_uri"),
            nowMs = deps.nowMs(),
        )
    val json =
        buildJsonObject {
            put("client_id", client.clientId)
            put("client_id_issued_at", deps.nowMs() / MILLIS_PER_SECOND)
            put("token_endpoint_auth_method", "none")
            putJsonArray("redirect_uris") { client.redirectUris.forEach { add(it) } }
            putJsonArray("grant_types") {
                add("authorization_code")
                add("refresh_token")
            }
            putJsonArray("response_types") { add("code") }
            client.clientName?.let { put("client_name", it) }
            client.applicationType?.let { put("application_type", it) }
            client.logoUri?.let { put("logo_uri", it) }
        }
    respondText(Json.encodeToString(json), ContentType.Application.Json, HttpStatusCode.Created)
    deps.serverLog.log(
        ServerLogEntry.Type.OAUTH,
        "OAuth client registered: ${client.clientName ?: client.clientId}",
    )
}

private suspend fun ApplicationCall.handleAuthorize(
    deps: OAuthRouteDeps,
    pendingAuthorize: PendingAuthorizeStore,
) {
    val params = request.queryParameters
    val clientId = params["client_id"]
    val redirectUri = params["redirect_uri"]
    val requester =
        when {
            clientId.isNullOrEmpty() || redirectUri.isNullOrEmpty() -> null
            OAuthPolicy.isMetadataDocumentClientId(clientId) -> metadataDocumentRequester(deps, clientId, redirectUri)
            else -> registeredRequester(deps, clientId, redirectUri)
        }
    if (requester == null || clientId == null || redirectUri == null) {
        // Open-redirect guard: never redirect to an unvalidated URI (nor when a CIMD document cannot be used).
        respondAuthorizeError("Invalid client_id or redirect_uri.")
        return
    }
    // The same base URL the metadata names as `issuer`, so the RFC 9207 `iss` matches it exactly.
    val issuer = effectiveBaseUrl(this, deps.publicUrlOverride)
    val canonical = canonicalResource(issuer)
    val state = params["state"].orEmpty()
    val paramError = authorizeParamError(params, canonical)
    if (paramError != null) {
        redirectWithError(redirectUri, paramError, state, issuer)
        return
    }
    val redirectHost = host(redirectUri) ?: "Unknown"
    val clientIp = clientIp()
    // Geolocation may inflate/mmap the bundled DB on first use — keep that disk I/O off the request thread.
    val clientGeo = withContext(Dispatchers.IO) { deps.geoIpResolver.resolve(clientIp) }
    // Before the approval exists, so the phone never asks while the browser still waits for its page.
    val embeddedLogo = ConsentLogo.embed(deps.httpFetcher, requester.logoUri)
    val approval =
        deps.approvalCoordinator.createPending(
            ApprovalRequest(
                clientName = requester.displayName,
                redirectHost = redirectHost,
                logoUri = requester.logoUri,
                clientIp = clientIp,
                clientGeo = clientGeo,
                loopbackRedirect = OAuthPolicy.isLoopbackRedirectUri(redirectUri),
                iconHost = requester.iconHost ?: redirectHost,
            ),
            deps.nowMs(),
        )
    deps.logAuthorizationRequested(requester.displayName, clientIp, clientGeo)
    pendingAuthorize.put(
        approval.id,
        PendingAuthorizeRequest(
            clientId = clientId,
            redirectUri = redirectUri,
            codeChallenge = params["code_challenge"].orEmpty(),
            resource = canonical,
            scope = params["scope"].orEmpty().ifEmpty { "mcp" },
            state = state,
            issuer = issuer,
            expiresAtMs = approval.expiresAtMs,
            metadataDocument = requester.metadataDocument,
        ),
        deps.nowMs(),
    )
    respondConsentPage(approval, requester.displayName, embeddedLogo, deps.nowMs())
}

/** Logs an OAUTH authorization-request entry annotated with the client's IP and geolocation (never tokens). */
private fun OAuthRouteDeps.logAuthorizationRequested(
    displayName: String,
    clientIp: String?,
    clientGeo: GeoLocation?,
) {
    val geoText =
        clientGeo
            ?.let { geo -> listOfNotNull(geo.city, geo.countryCode).joinToString(", ") }
            ?.takeIf { it.isNotEmpty() }
    val originText = listOfNotNull(clientIp, geoText).joinToString(" — ").takeIf { it.isNotEmpty() }
    serverLog.log(
        ServerLogEntry.Type.OAUTH,
        "OAuth authorization requested by $displayName" + (originText?.let { " from $it" } ?: ""),
    )
}

/** Validates the non-redirect authorize params; returns an OAuth error code, or null when valid. */
private fun authorizeParamError(
    params: Parameters,
    canonical: String,
): String? {
    val resourceParam = params["resource"]
    return when {
        params["response_type"] != "code" -> "unsupported_response_type"
        params["code_challenge_method"] != "S256" -> "invalid_request"
        params["code_challenge"].isNullOrEmpty() -> "invalid_request"
        !resourceParam.isNullOrEmpty() && !OAuthPolicy.resourceMatches(resourceParam, canonical) -> "invalid_target"
        else -> null
    }
}

private suspend fun ApplicationCall.handleAuthorizeStatus(
    deps: OAuthRouteDeps,
    pendingAuthorize: PendingAuthorizeStore,
) {
    val id = request.queryParameters["id"]
    if (id.isNullOrEmpty()) {
        respondStatus("expired")
        return
    }
    when (deps.approvalCoordinator.stateOf(id, deps.nowMs())) {
        ApprovalState.APPROVED -> {
            val pending = pendingAuthorize.consume(id, deps.nowMs())
            if (pending == null) {
                respondStatus("expired")
            } else {
                val code =
                    deps.authorizationCodeStore.create(
                        AuthorizationCodeRequest(
                            clientId = pending.clientId,
                            redirectUri = pending.redirectUri,
                            codeChallenge = pending.codeChallenge,
                            resource = pending.resource,
                            scope = pending.scope,
                            metadataDocument = pending.metadataDocument,
                        ),
                        deps.nowMs(),
                    )
                val redirect =
                    URLBuilder(pending.redirectUri)
                        .apply {
                            parameters.append("code", code)
                            parameters.append("state", pending.state)
                            parameters.append("iss", pending.issuer)
                        }.buildString()
                respondText(
                    Json.encodeToString(
                        buildJsonObject {
                            put("state", "approved")
                            put("redirect", redirect)
                        },
                    ),
                    ContentType.Application.Json,
                )
            }
        }

        ApprovalState.PENDING -> {
            respondStatus("pending")
        }

        ApprovalState.DENIED -> {
            respondStatus("denied")
        }

        ApprovalState.EXPIRED -> {
            respondStatus("expired")
        }
    }
}

private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringArray(key: String): List<String> =
    runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull() ?: emptyList()

private const val MILLIS_PER_SECOND = 1000L
