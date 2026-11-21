package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import java.net.URI

/**
 * Who is asking at `/authorize`: the name and logo to show, the host whose favicon stands in for a missing logo (the
 * document host for a CIMD client, whose redirect may be loopback; for DCR the app table's icon host, or null to use
 * the redirect host), and the CIMD document (null for a DCR client).
 */
internal class AuthorizeRequester(
    val displayName: String,
    val logoUri: String?,
    val metadataDocument: ClientMetadataDocument?,
    val iconHost: String? = null,
)

/** A DCR client: registered, with [redirectUri] among its registered URIs and still inside the allowlist. */
internal suspend fun registeredRequester(
    deps: OAuthRouteDeps,
    clientId: String,
    redirectUri: String,
): AuthorizeRequester? =
    deps.clientRepository
        .getClient(clientId)
        ?.takeIf { redirectUri in it.redirectUris && OAuthPolicy.isAllowedRedirectUri(redirectUri) }
        ?.let { client ->
            AuthorizeRequester(
                displayName = client.clientName ?: host(redirectUri) ?: "Unknown",
                logoUri = OAuthPolicy.displayLogoUri(clientId, client.logoUri),
                metadataDocument = null,
                iconHost = OAuthConnectors.iconHostFor(redirectUri),
            )
        }

/**
 * A CIMD client: its document is downloaded from an app's domain on every login, and [redirectUri] must be listed in it
 * and allowed for that domain. Each refusal is logged with what the client sent, so a new app can be added once its
 * real document is known.
 */
internal suspend fun metadataDocumentRequester(
    deps: OAuthRouteDeps,
    clientId: String,
    redirectUri: String,
): AuthorizeRequester? =
    when (val result = checkMetadataDocument(deps, clientId, redirectUri)) {
        is ClientMetadataResult.Invalid -> {
            deps.serverLog.log(
                ServerLogEntry.Type.OAUTH,
                "OAuth client metadata document refused (${loggable(clientId)}): ${result.reason}",
            )
            null
        }

        is ClientMetadataResult.Valid -> {
            val document = result.document
            AuthorizeRequester(
                displayName = OAuthPolicy.clientDisplayName(clientId, document.clientName) ?: document.clientName,
                logoUri = OAuthPolicy.displayLogoUri(clientId, document.logoUri),
                metadataDocument = document,
                iconHost = host(clientId),
            )
        }
    }

/** The document at [clientId] when it may be used with [redirectUri], else why not. */
private suspend fun checkMetadataDocument(
    deps: OAuthRouteDeps,
    clientId: String,
    redirectUri: String,
): ClientMetadataResult {
    val documentHost = OAuthPolicy.metadataDocumentHost(clientId)
    val result =
        if (documentHost == null) {
            ClientMetadataResult.Invalid("not on a supported app domain")
        } else {
            ClientMetadataDocuments.load(deps.httpFetcher, clientId)
        }
    val document = (result as? ClientMetadataResult.Valid)?.document
    return when {
        documentHost == null || document == null -> {
            result
        }

        !OAuthPolicy.isListedRedirectUri(redirectUri, document.redirectUris) -> {
            ClientMetadataResult.Invalid("redirect URI ${loggable(redirectUri)} is not in it")
        }

        !OAuthPolicy.isAllowedMetadataDocumentRedirect(documentHost, redirectUri) -> {
            ClientMetadataResult.Invalid("redirect URI ${loggable(redirectUri)} is not allowed for $documentHost")
        }

        else -> {
            result
        }
    }
}

/**
 * Client-sent text for the server log, at most [MAX_LOGGED_CHARS]: without control characters, and without Unicode
 * line and paragraph separators or format characters (bidi controls...), so it cannot fake a log line or reorder one.
 */
internal fun loggable(value: String): String =
    value
        .filterNot { it.isISOControl() || it.category in HIDDEN_CATEGORIES }
        .take(MAX_LOGGED_CHARS)

/** The host of [uri], or null. */
internal fun host(uri: String): String? = runCatching { URI(uri).host }.getOrNull()

private const val MAX_LOGGED_CHARS = 200

private val HIDDEN_CATEGORIES =
    setOf(CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR, CharCategory.FORMAT)
