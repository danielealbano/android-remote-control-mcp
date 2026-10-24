package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.geo.GeoLocation
import com.danielealbano.androidremotecontrolmcp.mcp.canonicalResource
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
import java.net.URI

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
    val client = if (clientId.isNullOrEmpty()) null else deps.clientRepository.getClient(clientId)
    val redirectUri = params["redirect_uri"]
    val redirectValid =
        client != null &&
            clientId != null &&
            !redirectUri.isNullOrEmpty() &&
            redirectUri in client.redirectUris &&
            OAuthPolicy.isAllowedRedirectUri(redirectUri)
    if (!redirectValid) {
        // Open-redirect guard: never redirect to an unvalidated URI.
        respondAuthorizeError("Invalid client_id or redirect_uri.")
        return
    }
    val canonical = canonicalResource(effectiveBaseUrl(this, deps.publicUrlOverride))
    val state = params["state"].orEmpty()
    val paramError = authorizeParamError(params, canonical)
    if (paramError != null) {
        redirectWithError(redirectUri, paramError, state)
        return
    }
    val redirectHost = host(redirectUri) ?: "Unknown"
    val displayName = client.clientName ?: redirectHost
    val clientIp = clientIp()
    // Geolocation may inflate/mmap the bundled DB on first use — keep that disk I/O off the request thread.
    val clientGeo = withContext(Dispatchers.IO) { deps.geoIpResolver.resolve(clientIp) }
    val approval =
        deps.approvalCoordinator.createPending(
            ApprovalRequest(
                clientName = displayName,
                redirectHost = redirectHost,
                logoUri = client.logoUri,
                clientIp = clientIp,
                clientGeo = clientGeo,
            ),
            deps.nowMs(),
        )
    deps.logAuthorizationRequested(displayName, clientIp, clientGeo)
    pendingAuthorize.put(
        approval.id,
        PendingAuthorizeRequest(
            clientId = clientId,
            redirectUri = redirectUri,
            codeChallenge = params["code_challenge"].orEmpty(),
            resource = canonical,
            scope = params["scope"].orEmpty().ifEmpty { "mcp" },
            state = state,
            expiresAtMs = approval.expiresAtMs,
        ),
        deps.nowMs(),
    )
    respondConsentPage(approval, displayName, client.logoUri, redirectHost, deps.nowMs())
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
                        ),
                        deps.nowMs(),
                    )
                val redirect =
                    URLBuilder(pending.redirectUri)
                        .apply {
                            parameters.append("code", code)
                            parameters.append("state", pending.state)
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

private fun host(uri: String): String? = runCatching { URI(uri).host }.getOrNull()

private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringArray(key: String): List<String> =
    runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull() ?: emptyList()

private const val MILLIS_PER_SECOND = 1000L
