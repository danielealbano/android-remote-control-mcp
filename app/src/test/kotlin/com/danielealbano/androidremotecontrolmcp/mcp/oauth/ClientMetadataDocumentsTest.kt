package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.integration.FakeOAuthHttpFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ClientMetadataDocuments")
class ClientMetadataDocumentsTest {
    private fun valid(result: ClientMetadataResult): ClientMetadataDocument =
        assertInstanceOf(ClientMetadataResult.Valid::class.java, result).document

    private fun invalidReason(result: ClientMetadataResult): String =
        assertInstanceOf(ClientMetadataResult.Invalid::class.java, result).reason

    @Test
    @DisplayName("parses the real Claude Code, ChatGPT, Codex and VS Code documents")
    fun parsesRealDocuments() {
        val claudeCode = valid(ClientMetadataDocuments.parse(CLAUDE_CODE_URL, CLAUDE_CODE_DOC))
        assertEquals("Claude Code", claudeCode.clientName)
        assertEquals(listOf("http://localhost/callback", "http://127.0.0.1/callback"), claudeCode.redirectUris)
        assertEquals(null, claudeCode.logoUri)
        // ChatGPT prefers private_key_jwt in the legacy singular field; the plural one also lists none.
        val chatGpt = valid(ClientMetadataDocuments.parse(CHATGPT_URL, CHATGPT_DOC))
        assertEquals("ChatGPT", chatGpt.clientName)
        assertEquals("https://persistent.oaistatic.com/sonic/misc/openai-logo.png", chatGpt.logoUri)
        assertEquals("Codex", valid(ClientMetadataDocuments.parse(CODEX_URL, CODEX_DOC)).clientName)
        assertEquals("Visual Studio Code", valid(ClientMetadataDocuments.parse(VSCODE_URL, VSCODE_DOC)).clientName)
    }

    @Test
    @DisplayName("refuses a document that does not name its own URL exactly")
    fun refusesClientIdMismatch() {
        assertEquals(
            "its client_id is not its URL",
            invalidReason(ClientMetadataDocuments.parse("$CLAUDE_CODE_URL/", CLAUDE_CODE_DOC)),
        )
        assertEquals(
            "its client_id is not its URL",
            invalidReason(ClientMetadataDocuments.parse(CLAUDE_CODE_URL, doc(clientId = "https://claude.ai/other"))),
        )
    }

    @Test
    @DisplayName("refuses missing or malformed required fields")
    fun refusesMissingFields() {
        assertEquals("it has no client_name", invalidReason(parse(doc(name = null))))
        assertEquals("it has no client_name", invalidReason(parse(doc(name = "\"  \""))))
        assertEquals("it has no redirect_uris", invalidReason(parse(doc(redirects = null))))
        assertEquals("it has no redirect_uris", invalidReason(parse(doc(redirects = "[]"))))
        assertEquals("redirect_uris is not a list of strings", invalidReason(parse(doc(redirects = "[1]"))))
        val notAList = "redirect_uris is not a list of strings"
        assertEquals(notAList, invalidReason(parse(doc(redirects = "\"http://localhost/cb\""))))
        assertEquals("not a JSON object", invalidReason(parse("[]")))
        assertEquals("not a JSON object", invalidReason(parse("not json")))
    }

    @Test
    @DisplayName("refuses grant and response types without the code flow")
    fun refusesWithoutCodeFlow() {
        assertEquals(
            "authorization_code not allowed",
            invalidReason(parse(doc(extra = ""","grant_types":["client_credentials"]"""))),
        )
        val tokenOnly = ""","response_types":["token"]"""
        assertEquals("response type code not allowed", invalidReason(parse(doc(extra = tokenOnly))))
        assertEquals(
            "grant_types is not a list of strings",
            invalidReason(parse(doc(extra = ""","grant_types":"authorization_code""""))),
        )
    }

    @Test
    @DisplayName("needs the none auth method, read from the plural field first")
    fun needsNoneAuthMethod() {
        val pluralWithoutNone =
            ""","token_endpoint_auth_methods_supported":["private_key_jwt"],""" +
                """"token_endpoint_auth_method":"none""""
        assertEquals("token endpoint auth method none not allowed", invalidReason(parse(doc(auth = pluralWithoutNone))))
        assertEquals(
            "token endpoint auth method none not allowed",
            invalidReason(parse(doc(auth = ""","token_endpoint_auth_method":"private_key_jwt""""))),
        )
        assertEquals("token endpoint auth method none not allowed", invalidReason(parse(doc(auth = ""))))
        valid(parse(doc(auth = ""","token_endpoint_auth_method":"none"""")))
    }

    @Test
    @DisplayName("refuses a document with a client secret")
    fun refusesClientSecret() {
        assertEquals("it has a client secret", invalidReason(parse(doc(extra = ""","client_secret":"s""""))))
        assertEquals("it has a client secret", invalidReason(parse(doc(extra = ""","client_secret_expires_at":0"""))))
    }

    @Test
    @DisplayName("load reads JSON media types only and reports a failed download")
    fun loadChecksMediaType() =
        runTest {
            fun fetcher(type: String?) =
                FakeOAuthHttpFetcher(
                    mapOf(CLAUDE_CODE_URL to FetchedBody(type, CLAUDE_CODE_DOC.encodeToByteArray())),
                )
            valid(ClientMetadataDocuments.load(fetcher("application/json; charset=utf-8"), CLAUDE_CODE_URL))
            valid(ClientMetadataDocuments.load(fetcher("application/client-metadata+json"), CLAUDE_CODE_URL))
            listOf("text/html", null).forEach { type ->
                val result = ClientMetadataDocuments.load(fetcher(type), CLAUDE_CODE_URL)
                assertEquals("not served as JSON: ${type ?: "none"}", invalidReason(result), type)
            }
            assertEquals(
                "could not be downloaded: ${FakeOAuthHttpFetcher.NOT_SERVED}",
                invalidReason(ClientMetadataDocuments.load(FakeOAuthHttpFetcher(), CLAUDE_CODE_URL)),
            )
        }

    @Test
    @DisplayName("load asks for at most 5 KB of the document")
    fun loadCapsSize() =
        runTest {
            val oversized = doc(extra = ""","padding":"${"x".repeat(OAuthPolicy.MAX_METADATA_DOCUMENT_BYTES)}"""")
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_CODE_URL to FakeOAuthHttpFetcher.json(oversized)))
            val result = ClientMetadataDocuments.load(fetcher, CLAUDE_CODE_URL)
            assertEquals("could not be downloaded: larger than 5120 bytes", invalidReason(result))
            assertEquals(listOf(CLAUDE_CODE_URL), fetcher.requested)
        }

    private fun parse(body: String) = ClientMetadataDocuments.parse(CLAUDE_CODE_URL, body)

    /** A Claude Code shaped document with one part changed; a null part is left out. */
    private fun doc(
        clientId: String = CLAUDE_CODE_URL,
        name: String? = "\"Claude Code\"",
        redirects: String? = """["http://localhost/callback"]""",
        auth: String = ""","token_endpoint_auth_method":"none"""",
        extra: String = "",
    ): String =
        buildString {
            append("""{"client_id":"$clientId"""")
            name?.let { append(""","client_name":$it""") }
            redirects?.let { append(""","redirect_uris":$it""") }
            append(auth)
            append(extra)
            append("}")
        }

    private companion object {
        // The real documents, read from each URL on 2026-11-21.
        const val CLAUDE_CODE_URL = "https://claude.ai/oauth/claude-code-client-metadata"
        const val CLAUDE_CODE_DOC =
            """{"client_id":"https://claude.ai/oauth/claude-code-client-metadata","client_name":"Claude Code",""" +
                """"client_uri":"https://claude.ai",""" +
                """"redirect_uris":["http://localhost/callback","http://127.0.0.1/callback"],""" +
                """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                """"token_endpoint_auth_method":"none"}"""
        const val CHATGPT_URL = "https://chatgpt.com/oauth/client.json"
        const val CHATGPT_DOC =
            """{"client_id":"https://chatgpt.com/oauth/client.json","client_uri":"https://chatgpt.com/",""" +
                """"redirect_uris":["https://chatgpt.com/connector_platform_oauth_redirect"],""" +
                """"token_endpoint_auth_method":"private_key_jwt",""" +
                """"token_endpoint_auth_methods_supported":["none","private_key_jwt"],""" +
                """"grant_types":["authorization_code","refresh_token",""" +
                """"urn:ietf:params:oauth:grant-type:jwt-bearer"],""" +
                """"response_types":["code"],"client_name":"ChatGPT",""" +
                """"logo_uri":"https://persistent.oaistatic.com/sonic/misc/openai-logo.png",""" +
                """"token_endpoint_auth_signing_alg":"RS256","jwks_uri":"https://chatgpt.com/oauth/jwks.json"}"""
        const val CODEX_URL = "https://chatgpt.com/oauth/codex/client.json"
        const val CODEX_DOC =
            """{"client_id":"https://chatgpt.com/oauth/codex/client.json","client_uri":"https://chatgpt.com/codex",""" +
                """"application_type":"native",""" +
                """"redirect_uris":["http://127.0.0.1/callback","http://localhost/callback"],""" +
                """"token_endpoint_auth_method":"none","token_endpoint_auth_methods_supported":["none"],""" +
                """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                """"client_name":"Codex","logo_uri":"https://persistent.oaistatic.com/sonic/misc/openai-logo.png"}"""
        const val VSCODE_URL = "https://vscode.dev/oauth/client-metadata.json"
        const val VSCODE_DOC =
            """{"client_name":"Visual Studio Code",""" +
                """"logo_uri":"https://code.visualstudio.com/assets/branding/code-stable.png",""" +
                """"grant_types":["authorization_code","refresh_token",""" +
                """"urn:ietf:params:oauth:grant-type:device_code"],""" +
                """"response_types":["code"],"token_endpoint_auth_method":"none","application_type":"native",""" +
                """"client_id":"https://vscode.dev/oauth/client-metadata.json",""" +
                """"client_uri":"https://vscode.dev/product",""" +
                """"redirect_uris":["http://127.0.0.1:33418/","https://vscode.dev/redirect"]}"""
    }
}
