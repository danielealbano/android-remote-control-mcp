package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The parts of a Client ID Metadata Document (draft-ietf-oauth-client-id-metadata-document) this server uses. With
 * CIMD a client's `client_id` is an HTTPS URL, and the document served there names the client and its redirect URIs.
 */
data class ClientMetadataDocument(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String>,
    val logoUri: String?,
)

/** The outcome of loading a [ClientMetadataDocument]. */
sealed interface ClientMetadataResult {
    data class Valid(
        val document: ClientMetadataDocument,
    ) : ClientMetadataResult

    /** [reason] is for the server log; it never reaches the client. */
    data class Invalid(
        val reason: String,
    ) : ClientMetadataResult
}

object ClientMetadataDocuments {
    /**
     * Downloads the document at [clientId] through [fetcher] and checks it. A document that cannot be downloaded, is
     * not served as JSON or fails [parse] is refused; nothing is cached, so every login reads it fresh.
     */
    suspend fun load(
        fetcher: OAuthHttpFetcher,
        clientId: String,
    ): ClientMetadataResult {
        val result = fetcher.get(clientId, JSON_MEDIA_TYPE, OAuthPolicy.MAX_METADATA_DOCUMENT_BYTES)
        val body = (result as? FetchResult.Fetched)?.body
        return when {
            result is FetchResult.Failed -> ClientMetadataResult.Invalid("could not be downloaded: ${result.reason}")
            body == null -> ClientMetadataResult.Invalid("could not be downloaded")
            !isJsonMediaType(body.contentType) -> notJson(body.contentType)
            else -> parse(clientId, body.bytes.decodeToString())
        }
    }

    /**
     * Checks a document fetched from [clientId]: a JSON object whose `client_id` is exactly [clientId], a non-blank
     * `client_name` and a non-empty `redirect_uris` (the fields the MCP authorization spec requires), `grant_types`
     * and `response_types` allowing the authorization code flow when present, the public-client method `none` among
     * its token endpoint auth methods (the plural field first, the singular one otherwise), and no client secret.
     */
    fun parse(
        clientId: String,
        body: String,
    ): ClientMetadataResult {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val clientName = json?.string("client_name")?.takeIf { it.isNotBlank() }
        val problem = if (json == null) "not a JSON object" else problemOf(clientId, json, clientName)
        return if (json != null && clientName != null && problem == null) {
            ClientMetadataResult.Valid(
                ClientMetadataDocument(
                    clientId = clientId,
                    clientName = clientName,
                    redirectUris = json.strings("redirect_uris").orEmpty(),
                    logoUri = json.string("logo_uri"),
                ),
            )
        } else {
            ClientMetadataResult.Invalid(problem ?: "it has no client_name")
        }
    }

    /** Why [json] (fetched from [clientId], named [clientName]) cannot be used, or null. */
    private fun problemOf(
        clientId: String,
        json: JsonObject,
        clientName: String?,
    ): String? {
        val malformedList = LIST_FIELDS.firstOrNull { it in json && json.strings(it) == null }
        return when {
            json.string("client_id") != clientId -> "its client_id is not its URL"
            malformedList != null -> "$malformedList is not a list of strings"
            clientName == null -> "it has no client_name"
            json.strings("redirect_uris").isNullOrEmpty() -> "it has no redirect_uris"
            json.strings("grant_types")?.contains("authorization_code") == false -> "authorization_code not allowed"
            json.strings("response_types")?.contains("code") == false -> "response type code not allowed"
            !usesPublicClientAuth(json) -> "token endpoint auth method none not allowed"
            "client_secret" in json || "client_secret_expires_at" in json -> "it has a client secret"
            else -> null
        }
    }

    /** This server only offers `none`: the client must allow it (section 5 of the CIMD draft). */
    private fun usesPublicClientAuth(json: JsonObject): Boolean {
        val methods = json.strings("token_endpoint_auth_methods_supported")
        return if (methods != null) "none" in methods else json.string("token_endpoint_auth_method") == "none"
    }

    private fun notJson(contentType: String?) = ClientMetadataResult.Invalid("not served as JSON: $contentType")

    private fun isJsonMediaType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == JSON_MEDIA_TYPE || (type.startsWith("application/") && type.endsWith("+json"))
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive
        return primitive?.takeIf { it.isString }?.contentOrNull
    }

    /** The string items of an array field; null when the field is absent or not an array of strings. */
    private fun JsonObject.strings(key: String): List<String>? {
        val array = this[key] as? JsonArray ?: return null
        val items = array.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull }
        return items.filterNotNull().takeIf { it.size == items.size }
    }

    private const val JSON_MEDIA_TYPE = "application/json"

    private val LIST_FIELDS =
        listOf("redirect_uris", "grant_types", "response_types", "token_endpoint_auth_methods_supported")
}
