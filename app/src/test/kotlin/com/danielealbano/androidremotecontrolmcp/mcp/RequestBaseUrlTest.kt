package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.http.RequestConnectionPoint
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.util.Attributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RequestBaseUrl derivation")
class RequestBaseUrlTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(
            "io.ktor.server.request.ApplicationRequestPropertiesKt",
            "io.ktor.server.plugins.OriginConnectionPointKt",
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(
            "io.ktor.server.request.ApplicationRequestPropertiesKt",
            "io.ktor.server.plugins.OriginConnectionPointKt",
        )
    }

    /** The listener connection point used only on the last-resort fallback (no Host / forwarded headers). */
    private data class Listener(
        val host: String = "listener.local",
        val port: Int = 80,
        val scheme: String = "http",
    )

    private fun mockCall(
        forwardedHost: String? = null,
        forwardedProto: String? = null,
        hostHeader: String? = null,
        tunnelListener: Boolean = false,
        listener: Listener = Listener(),
        localPort: Int = listener.port,
    ): ApplicationCall {
        val headerPairs = mutableListOf<Pair<String, List<String>>>()
        forwardedHost?.let { headerPairs += "X-Forwarded-Host" to listOf(it) }
        forwardedProto?.let { headerPairs += "X-Forwarded-Proto" to listOf(it) }
        hostHeader?.let { headerPairs += "Host" to listOf(it) }
        val headers = headersOf(*headerPairs.toTypedArray())

        val request = mockk<ApplicationRequest>()
        every { request.headers } returns headers
        every { request.host() } returns listener.host
        every { request.port() } returns listener.port
        val connectionPoint = mockk<RequestConnectionPoint>()
        every { connectionPoint.scheme } returns listener.scheme
        every { connectionPoint.localPort } returns localPort
        every { request.origin } returns connectionPoint

        val attributes = Attributes()
        if (tunnelListener) attributes.put(TunnelListenerRequest, Unit)
        val call = mockk<ApplicationCall>()
        every { call.request } returns request
        every { call.attributes } returns attributes
        return call
    }

    @Test
    @DisplayName("on the tunnel listener, no Host and the local socket's port gives no port")
    fun tunnelListenerDropsTheLoopbackPort() {
        val call =
            mockCall(
                tunnelListener = true,
                listener = Listener(host = "name.d.example.test", port = 62835, scheme = "https"),
                localPort = 62835,
            )
        assertEquals("https://name.d.example.test", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("on the tunnel listener, a port other than the local socket's is one the client named")
    fun tunnelListenerKeepsANamedPort() {
        val call =
            mockCall(
                tunnelListener = true,
                listener = Listener(host = "name.d.example.test", port = 18443, scheme = "https"),
                localPort = 62835,
            )
        assertEquals("https://name.d.example.test:18443", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("on any other listener, the local socket's port is the one the client connected to")
    fun otherListenersKeepTheLocalPort() {
        val call =
            mockCall(
                listener = Listener(host = "192.168.1.5", port = 8443, scheme = "https"),
                localPort = 8443,
            )
        assertEquals("https://192.168.1.5:8443", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("on the tunnel listener, X-Forwarded-Host and Host still win")
    fun tunnelListenerStillPrefersTheHeaders() {
        val forwarded = mockCall(forwardedHost = "forwarded.example:1234", tunnelListener = true)
        val host = mockCall(hostHeader = "host.example:1234", tunnelListener = true)
        assertEquals("http://forwarded.example:1234", deriveBaseUrl(forwarded))
        assertEquals("http://host.example:1234", deriveBaseUrl(host))
    }

    @Test
    @DisplayName("uses X-Forwarded-Host and X-Forwarded-Proto when present")
    fun usesForwardedHeaders() {
        val call = mockCall(forwardedHost = "tunnel.example", forwardedProto = "https")
        assertEquals("https://tunnel.example", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("falls back to Host header and origin scheme")
    fun fallsBackToHostAndScheme() {
        val call = mockCall(listener = Listener(host = "listener.local", port = 8080, scheme = "http"))
        assertEquals("http://listener.local:8080", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("HTTPS tunnel with portless Host header does not append the local http default port")
    fun httpsTunnelPortlessHostNoBogusPort() {
        // cloudflared/ngrok terminate TLS and forward plaintext http; the Host header carries no port,
        // so reconstructing host():port() would synthesize the http default (80) and yield https://host:80.
        val tunnelHost = "stats-taxes-killing-exterior.trycloudflare.com"
        val call =
            mockCall(
                forwardedProto = "https",
                hostHeader = tunnelHost,
                listener = Listener(host = tunnelHost, port = 80, scheme = "http"),
            )
        assertEquals("https://$tunnelHost", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("uses explicit port from Host header when present")
    fun usesExplicitHostHeaderPort() {
        val call =
            mockCall(
                hostHeader = "192.168.1.5:8080",
                listener = Listener(host = "192.168.1.5", port = 8080, scheme = "http"),
            )
        assertEquals("http://192.168.1.5:8080", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("omits default port")
    fun omitsDefaultPort() {
        assertEquals(
            "https://tunnel.example",
            deriveBaseUrl(mockCall(forwardedHost = "tunnel.example:443", forwardedProto = "https")),
        )
        assertEquals(
            "http://tunnel.example",
            deriveBaseUrl(mockCall(forwardedHost = "tunnel.example:80", forwardedProto = "http")),
        )
        assertEquals(
            "https://tunnel.example:8443",
            deriveBaseUrl(mockCall(forwardedHost = "tunnel.example:8443", forwardedProto = "https")),
        )
    }

    @Test
    @DisplayName("lowercases scheme and host and strips trailing slash")
    fun normalizes() {
        assertEquals("https://example.com", normalizeBaseUrl("HTTPS://Example.COM/"))
        assertEquals("http://host.local:9090", normalizeBaseUrl("HTTP://Host.Local:9090"))
    }

    @Test
    @DisplayName("takes first value of comma-separated forwarded header")
    fun takesFirstForwardedValue() {
        val call = mockCall(forwardedHost = "a.example, b.example", forwardedProto = "https, http")
        assertEquals("https://a.example", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("effectiveBaseUrl returns normalized override when set")
    fun effectiveOverrideWins() {
        val call = mockCall(forwardedHost = "tunnel.example", forwardedProto = "https")
        assertEquals("https://pinned.host", effectiveBaseUrl(call, "HTTPS://Pinned.Host/"))
    }

    @Test
    @DisplayName("effectiveBaseUrl falls back to derive when override blank")
    fun effectiveFallsBackWhenBlank() {
        val call = mockCall(forwardedHost = "tunnel.example", forwardedProto = "https")
        assertEquals(deriveBaseUrl(call), effectiveBaseUrl(call, "   "))
    }

    @Test
    @DisplayName("currentRequestBaseUrl returns element value, else fallback")
    fun currentRequestBaseUrlReadsElement() =
        runTest {
            val viaElement =
                withContext(RequestBaseUrlElement("https://from-element")) {
                    currentRequestBaseUrl { "https://fallback" }
                }
            assertEquals("https://from-element", viaElement)
            assertEquals("https://fallback", currentRequestBaseUrl { "https://fallback" })
        }

    @Test
    @DisplayName("context element survives an inner withContext(Dispatchers.IO)")
    fun elementSurvivesIoHop() =
        runTest {
            val value =
                withContext(RequestBaseUrlElement("https://from-element")) {
                    withContext(Dispatchers.IO) {
                        currentRequestBaseUrl { "https://fallback" }
                    }
                }
            assertEquals("https://from-element", value)
        }

    @Test
    @DisplayName("canonicalResource appends /mcp")
    fun canonicalResourceAppendsMcp() {
        assertEquals("https://host.example/mcp", canonicalResource("https://host.example"))
    }
}
