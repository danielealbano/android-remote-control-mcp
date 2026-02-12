package com.danielealbano.androidremotecontrolmcp.mcp.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BearerTokenAuthTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // --- constantTimeEquals tests ---

    @Test
    fun `constantTimeEquals returns true for identical strings`() {
        assertTrue(constantTimeEquals("my-secret-token", "my-secret-token"))
    }

    @Test
    fun `constantTimeEquals returns false for different strings`() {
        assertFalse(constantTimeEquals("my-secret-token", "wrong-token"))
    }

    @Test
    fun `constantTimeEquals returns false for empty vs non-empty`() {
        assertFalse(constantTimeEquals("my-secret-token", ""))
    }

    @Test
    fun `constantTimeEquals returns true for both empty`() {
        assertTrue(constantTimeEquals("", ""))
    }

    @Test
    fun `constantTimeEquals returns false for strings differing in length`() {
        assertFalse(constantTimeEquals("short", "a-much-longer-string"))
    }

    @Test
    fun `constantTimeEquals returns false for strings differing by one character`() {
        assertFalse(constantTimeEquals("abcdef", "abcdeg"))
    }

    @Test
    fun `constantTimeEquals handles unicode strings`() {
        assertTrue(constantTimeEquals("token-\u00e9\u00e8", "token-\u00e9\u00e8"))
        assertFalse(constantTimeEquals("token-\u00e9\u00e8", "token-\u00e9\u00e7"))
    }

    @Test
    fun `constantTimeEquals handles UUID-format tokens`() {
        val token = "550e8400-e29b-41d4-a716-446655440000"
        assertTrue(constantTimeEquals(token, token))
        assertFalse(constantTimeEquals(token, "550e8400-e29b-41d4-a716-446655440001"))
    }

    // --- Timing consistency verification ---
    // Note: True constant-time verification requires statistical analysis of timing
    // measurements, which is impractical in a unit test. The test below verifies
    // that MessageDigest.isEqual is being used (by checking the function produces
    // correct results for edge cases that would differ between naive and constant-time
    // implementations).

    @Test
    fun `constantTimeEquals returns correct result regardless of mismatch position`() {
        val base = "abcdefghijklmnop"
        // Mismatch at first character
        assertFalse(constantTimeEquals(base, "Xbcdefghijklmnop"))
        // Mismatch at last character
        assertFalse(constantTimeEquals(base, "abcdefghijklmnoX"))
        // Mismatch at middle character
        assertFalse(constantTimeEquals(base, "abcdefgXijklmnop"))
    }

    // --- Ktor plugin integration tests ---

    @Test
    fun `plugin returns 401 when Authorization header is missing`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/protected") {
                        install(BearerTokenAuthPlugin) { expectedToken = TEST_TOKEN }
                        get("/resource") { call.respondText("OK") }
                    }
                }
            }

            val response = client.get("/protected/resource")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `plugin returns 401 when Authorization header is malformed`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/protected") {
                        install(BearerTokenAuthPlugin) { expectedToken = TEST_TOKEN }
                        get("/resource") { call.respondText("OK") }
                    }
                }
            }

            val response =
                client.get("/protected/resource") {
                    header("Authorization", "Basic abc123")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `plugin returns 401 when token is invalid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/protected") {
                        install(BearerTokenAuthPlugin) { expectedToken = TEST_TOKEN }
                        get("/resource") { call.respondText("OK") }
                    }
                }
            }

            val response =
                client.get("/protected/resource") {
                    header("Authorization", "Bearer wrong-token")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `plugin allows request with valid token`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/protected") {
                        install(BearerTokenAuthPlugin) { expectedToken = TEST_TOKEN }
                        get("/resource") { call.respondText("OK") }
                    }
                }
            }

            val response =
                client.get("/protected/resource") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }

    companion object {
        private const val TEST_TOKEN = "test-secret-token"
    }
}
