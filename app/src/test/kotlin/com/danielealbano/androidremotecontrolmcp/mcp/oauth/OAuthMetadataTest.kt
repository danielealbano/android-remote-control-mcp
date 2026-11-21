package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthMetadata")
class OAuthMetadataTest {
    private val base = "https://host.example"

    @Test
    @DisplayName("protected resource metadata pins the expected fields")
    fun prmFields() {
        val obj = Json.parseToJsonElement(OAuthMetadata.protectedResourceMetadata(base)).jsonObject
        assertEquals("$base/mcp", obj["resource"]?.jsonPrimitive?.content)
        assertEquals(listOf(base), obj["authorization_servers"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("header"), obj["bearer_methods_supported"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("mcp"), obj["scopes_supported"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    @DisplayName("authorization server metadata pins the expected fields")
    fun asFields() {
        val obj = Json.parseToJsonElement(OAuthMetadata.authorizationServerMetadata(base)).jsonObject
        assertEquals(base, obj["issuer"]?.jsonPrimitive?.content)
        assertEquals(true, obj["authorization_response_iss_parameter_supported"]?.jsonPrimitive?.boolean)
        assertEquals("$base/authorize", obj["authorization_endpoint"]?.jsonPrimitive?.content)
        assertEquals("$base/token", obj["token_endpoint"]?.jsonPrimitive?.content)
        assertEquals("$base/register", obj["registration_endpoint"]?.jsonPrimitive?.content)
        assertEquals(listOf("code"), obj["response_types_supported"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("authorization_code", "refresh_token"),
            obj["grant_types_supported"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("S256"),
            obj["code_challenge_methods_supported"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("none"),
            obj["token_endpoint_auth_methods_supported"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("mcp", "offline_access"),
            obj["scopes_supported"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }
}
