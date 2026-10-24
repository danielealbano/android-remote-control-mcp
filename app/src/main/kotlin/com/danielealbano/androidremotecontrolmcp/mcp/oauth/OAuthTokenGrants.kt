package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.canonicalResource
import com.danielealbano.androidremotecontrolmcp.mcp.effectiveBaseUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** `POST /token` — dispatches by `grant_type`. */
internal suspend fun ApplicationCall.handleToken(deps: OAuthRouteDeps) {
    val form = receiveParameters()
    when (form["grant_type"]) {
        "authorization_code" -> handleAuthorizationCodeGrant(deps, form)
        "refresh_token" -> handleRefreshTokenGrant(deps, form)
        else -> respondOAuthError(HttpStatusCode.BadRequest, "unsupported_grant_type")
    }
}

private suspend fun ApplicationCall.handleAuthorizationCodeGrant(
    deps: OAuthRouteDeps,
    form: Parameters,
) {
    val code = form["code"]?.let { deps.authorizationCodeStore.consume(it, deps.nowMs()) }
    val clientId = form["client_id"]
    val resourceForm = form["resource"]
    val errorCode: String? =
        when {
            code == null -> {
                "invalid_grant"
            }

            form["redirect_uri"] != code.redirectUri -> {
                "invalid_grant"
            }

            clientId.isNullOrEmpty() -> {
                "invalid_request"
            }

            clientId != code.clientId -> {
                "invalid_grant"
            }

            !resourceForm.isNullOrEmpty() && !OAuthPolicy.resourceMatches(resourceForm, code.resource) -> {
                "invalid_grant"
            }

            !Pkce.verifyS256(form["code_verifier"].orEmpty(), code.codeChallenge) -> {
                "invalid_grant"
            }

            else -> {
                null
            }
        }
    if (errorCode != null || code == null) {
        respondOAuthError(HttpStatusCode.BadRequest, errorCode ?: "invalid_grant")
        return
    }
    val jti = UUID.randomUUID().toString()
    deps.clientRepository.setRefreshJti(code.clientId, jti)
    deps.clientRepository.touchLastUsed(code.clientId, deps.nowMs())
    val clientName = deps.clientRepository.getClient(code.clientId)?.clientName ?: code.clientId
    deps.serverLog.log(ServerLogEntry.Type.OAUTH, "OAuth tokens issued to $clientName")
    respondTokens(
        access = deps.tokenService.issueAccessToken(code.clientId, code.resource),
        refresh = deps.tokenService.issueRefreshToken(code.clientId, jti, code.resource),
    )
}

private suspend fun ApplicationCall.handleRefreshTokenGrant(
    deps: OAuthRouteDeps,
    form: Parameters,
) {
    val claims = deps.tokenService.verifyRefreshToken(form["refresh_token"].orEmpty())
    val clientIdForm = form["client_id"]
    val client = claims?.let { deps.clientRepository.getClient(it.clientId) }
    val rejected =
        claims == null ||
            (!clientIdForm.isNullOrEmpty() && clientIdForm != claims.clientId) ||
            client == null ||
            // Stale/rotated refresh token — reject only (do NOT revoke the client).
            claims.jti != client.currentRefreshJti
    if (rejected) {
        respondOAuthError(HttpStatusCode.BadRequest, "invalid_grant")
        return
    }
    val resource = canonicalResource(effectiveBaseUrl(this, deps.publicUrlOverride))
    val newJti = UUID.randomUUID().toString()
    deps.clientRepository.setRefreshJti(claims.clientId, newJti)
    deps.clientRepository.touchLastUsed(claims.clientId, deps.nowMs())
    deps.serverLog.log(
        ServerLogEntry.Type.OAUTH,
        "OAuth token refreshed for ${client.clientName ?: claims.clientId}",
    )
    respondTokens(
        access = deps.tokenService.issueAccessToken(claims.clientId, resource),
        refresh = deps.tokenService.issueRefreshToken(claims.clientId, newJti, resource),
    )
}

internal suspend fun ApplicationCall.respondTokens(
    access: String,
    refresh: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(
        Json.encodeToString(
            buildJsonObject {
                put("access_token", access)
                put("token_type", "Bearer")
                put("expires_in", OAuthPolicy.ACCESS_TOKEN_TTL_SECONDS)
                put("refresh_token", refresh)
                put("scope", "mcp")
            },
        ),
        ContentType.Application.Json,
    )
}

internal suspend fun ApplicationCall.respondStatus(state: String) {
    respondText(
        Json.encodeToString(buildJsonObject { put("state", state) }),
        ContentType.Application.Json,
    )
}

internal suspend fun ApplicationCall.respondOAuthError(
    status: HttpStatusCode,
    error: String,
) {
    respondText(
        Json.encodeToString(buildJsonObject { put("error", error) }),
        ContentType.Application.Json,
        status,
    )
}

internal suspend fun ApplicationCall.respondAuthorizeError(message: String) {
    respondText(
        "<!DOCTYPE html><html><body><h1>Authorization error</h1><p>$message</p></body></html>",
        ContentType.Text.Html,
        HttpStatusCode.BadRequest,
    )
}

internal suspend fun ApplicationCall.redirectWithError(
    redirectUri: String,
    error: String,
    state: String,
) {
    val url =
        URLBuilder(redirectUri)
            .apply {
                parameters.append("error", error)
                if (state.isNotEmpty()) parameters.append("state", state)
            }.buildString()
    respondRedirect(url)
}
