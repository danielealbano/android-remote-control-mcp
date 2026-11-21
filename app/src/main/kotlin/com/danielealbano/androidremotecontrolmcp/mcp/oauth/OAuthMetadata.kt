package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OAuth discovery documents. PRM is RFC 9728; AS metadata is RFC 8414. The field sets are exactly those
 * the spike confirmed Claude.ai completes discovery + authorization against, plus the `offline_access`
 * scope ChatGPT relies on to obtain and maintain refresh-token connectivity, the RFC 9207
 * `authorization_response_iss_parameter_supported` flag, and `client_id_metadata_document_supported` (CIMD).
 */
object OAuthMetadata {
    /** RFC 9728 Protected Resource Metadata for `<baseUrl>/mcp`. */
    fun protectedResourceMetadata(baseUrl: String): String =
        Json.encodeToString(
            buildJsonObject {
                put("resource", "$baseUrl/mcp")
                putJsonArray("authorization_servers") { add(baseUrl) }
                putJsonArray("bearer_methods_supported") { add("header") }
                putJsonArray("scopes_supported") { add("mcp") }
            },
        )

    /**
     * RFC 8414 Authorization Server Metadata for issuer `<baseUrl>`. It advertises RFC 9207 issuer identification:
     * every authorization response carries `iss` = this exact issuer. It also advertises Client ID Metadata Documents
     * next to DCR: Claude, ChatGPT, Codex and VS Code then send their document URL as `client_id` (with `none`
     * listed below, Claude's other condition), while Gemini keeps using `registration_endpoint`.
     */
    fun authorizationServerMetadata(baseUrl: String): String =
        Json.encodeToString(
            buildJsonObject {
                put("issuer", baseUrl)
                put("authorization_response_iss_parameter_supported", true)
                put("authorization_endpoint", "$baseUrl/authorize")
                put("token_endpoint", "$baseUrl/token")
                put("registration_endpoint", "$baseUrl/register")
                put("client_id_metadata_document_supported", true)
                putJsonArray("response_types_supported") { add("code") }
                putJsonArray("grant_types_supported") {
                    add("authorization_code")
                    add("refresh_token")
                }
                putJsonArray("code_challenge_methods_supported") { add("S256") }
                putJsonArray("token_endpoint_auth_methods_supported") { add("none") }
                putJsonArray("scopes_supported") {
                    add("mcp")
                    // Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130.
                    add("offline_access")
                }
            },
        )
}
