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
        http2Authority: String? = null,
        listener: Listener = Listener(),
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
        every { request.origin } returns connectionPoint

        val attributes = Attributes()
        http2Authority?.let { attributes.put(Http2Authority, it) }
        val call = mockk<ApplicationCall>()
        every { call.request } returns request
        every { call.attributes } returns attributes
        return call
    }

    @Test
    @DisplayName("an HTTP/2 authority with no port gives no port, whatever port the socket has")
    fun http2AuthorityWithNoPort() {
        val call =
            mockCall(
                http2Authority = "name.d.example.test",
                listener = Listener(host = "name.d.example.test", port = 62835, scheme = "https"),
            )
        assertEquals("https://name.d.example.test", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("an HTTP/2 authority naming the socket's own port keeps it")
    fun http2AuthorityNamingTheSocketPort() {
        val call =
            mockCall(
                http2Authority = "192.168.1.5:8443",
                listener = Listener(host = "192.168.1.5", port = 8443, scheme = "https"),
            )
        assertEquals("https://192.168.1.5:8443", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("an HTTP/2 authority naming the scheme's default port gives no port")
    fun http2AuthorityNamingTheDefaultPort() {
        val call =
            mockCall(
                http2Authority = "name.d.example.test:443",
                listener = Listener(host = "name.d.example.test", port = 443, scheme = "https"),
            )
        assertEquals("https://name.d.example.test", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("an HTTP/2 authority with an IPv6 address keeps the brackets, with and without a port")
    fun http2AuthorityWithIpv6() {
        val listener = Listener(host = "[::1]", port = 8443, scheme = "https")
        assertEquals("https://[::1]", deriveBaseUrl(mockCall(http2Authority = "[::1]", listener = listener)))
        assertEquals("https://[::1]:8443", deriveBaseUrl(mockCall(http2Authority = "[::1]:8443", listener = listener)))
    }

    @Test
    @DisplayName("an HTTP/2 authority listing several hosts gives the first, as Host does")
    fun http2AuthorityListingSeveralHosts() {
        val fromAuthority = mockCall(http2Authority = "a.example, b.example")
        val fromHost = mockCall(hostHeader = "a.example, b.example")
        assertEquals("http://a.example", deriveBaseUrl(fromAuthority))
        assertEquals(deriveBaseUrl(fromHost), deriveBaseUrl(fromAuthority))
    }

    @Test
    @DisplayName("an empty HTTP/2 authority falls back to the connection point")
    fun emptyHttp2Authority() {
        val call =
            mockCall(
                http2Authority = "",
                listener = Listener(host = "192.168.1.5", port = 8443, scheme = "https"),
            )
        assertEquals("https://192.168.1.5:8443", deriveBaseUrl(call))
    }

    @Test
    @DisplayName("X-Forwarded-Host and Host win over an HTTP/2 authority")
    fun headersWinOverHttp2Authority() {
        val forwarded = mockCall(forwardedHost = "forwarded.example:1234", http2Authority = "authority.example")
        val host = mockCall(hostHeader = "host.example:1234", http2Authority = "authority.example")
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
