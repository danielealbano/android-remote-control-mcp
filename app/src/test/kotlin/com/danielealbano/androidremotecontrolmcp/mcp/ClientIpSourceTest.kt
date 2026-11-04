package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import io.ktor.http.RequestConnectionPoint
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.Attributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("ApplicationCall.clientIp")
class ClientIpSourceTest {
    @BeforeEach
    fun setUp() {
        mockkStatic("io.ktor.server.plugins.OriginConnectionPointKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("io.ktor.server.plugins.OriginConnectionPointKt")
    }

    /** The headers default to spoofed values, what any client can send. */
    private fun call(
        source: ClientIpSource? = null,
        cfConnectingIp: String? = SPOOFED_CF,
        xForwardedFor: List<String>? = listOf(SPOOFED_XFF),
        remoteAddress: String = LOOPBACK,
    ): ApplicationCall {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        cfConnectingIp?.let { pairs += "CF-Connecting-IP" to listOf(it) }
        xForwardedFor?.let { pairs += "X-Forwarded-For" to it }

        val request = mockk<ApplicationRequest>()
        every { request.headers } returns headersOf(*pairs.toTypedArray())
        val connectionPoint = mockk<RequestConnectionPoint>()
        every { connectionPoint.remoteAddress } returns remoteAddress
        every { request.origin } returns connectionPoint

        val attributes = Attributes()
        source?.let { attributes.put(ClientIpSourceKey, it) }
        return mockk {
            every { this@mockk.request } returns request
            every { this@mockk.attributes } returns attributes
        }
    }

    @Test
    @DisplayName("a call no listener marked trusts no header")
    fun unmarkedTrustsNoHeader() {
        assertEquals(LOOPBACK, call().clientIp())
    }

    @Test
    @DisplayName("SOCKET_PEER trusts no header")
    fun socketPeerTrustsNoHeader() {
        assertEquals(LOOPBACK, call(ClientIpSource.SOCKET_PEER).clientIp())
    }

    @Test
    @DisplayName("CLOUDFLARE reads CF-Connecting-IP and never X-Forwarded-For")
    fun cloudflareReadsItsHeader() {
        assertEquals(SPOOFED_CF, call(ClientIpSource.CLOUDFLARE).clientIp())
        assertEquals(LOOPBACK, call(ClientIpSource.CLOUDFLARE, cfConnectingIp = null).clientIp())
    }

    @Test
    @DisplayName("NGROK reads the last X-Forwarded-For line, the one ngrok adds after what the client sent")
    fun ngrokReadsTheLastLine() {
        val call = call(ClientIpSource.NGROK, xForwardedFor = listOf("7.7.7.7, 8.8.8.8", REAL))
        assertEquals(REAL, call.clientIp())
    }

    @Test
    @DisplayName("NGROK reads the last value when everything arrives on one line")
    fun ngrokReadsTheLastValueOfALine() {
        assertEquals(REAL, call(ClientIpSource.NGROK, xForwardedFor = listOf("7.7.7.7, $REAL")).clientIp())
    }

    @Test
    @DisplayName("NGROK never reads CF-Connecting-IP, which ngrok passes through from the client")
    fun ngrokIgnoresTheCloudflareHeader() {
        assertEquals(REAL, call(ClientIpSource.NGROK, xForwardedFor = listOf(REAL)).clientIp())
        assertEquals(LOOPBACK, call(ClientIpSource.NGROK, xForwardedFor = null).clientIp())
    }

    @Test
    @DisplayName("UNKNOWN reports no address, so the free tunnel's loopback peer never reads as this phone")
    fun unknownReportsNoAddress() {
        assertNull(call(ClientIpSource.UNKNOWN).clientIp())
        assertNull(call(ClientIpSource.UNKNOWN, remoteAddress = LAN_PEER).clientIp())
    }

    @Test
    @DisplayName("a forwarded value that is not an IP literal is ignored")
    fun textIsNotAnAddress() {
        assertEquals(LOOPBACK, call(ClientIpSource.CLOUDFLARE, cfConnectingIp = "Verified, safe").clientIp())
        assertEquals(LOOPBACK, call(ClientIpSource.CLOUDFLARE, cfConnectingIp = "cafe").clientIp())
        assertEquals(LOOPBACK, call(ClientIpSource.NGROK, xForwardedFor = listOf("Verified, safe")).clientIp())
        assertEquals("2001:db8::7", call(ClientIpSource.CLOUDFLARE, cfConnectingIp = "2001:db8::7").clientIp())
    }

    @ParameterizedTest
    @EnumSource(ClientIpSource::class, names = ["UNKNOWN"], mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a peer that is not loopback is the client itself, whatever the headers say")
    fun anotherPeerIsTheClient(source: ClientIpSource) {
        assertEquals(LAN_PEER, call(source, remoteAddress = LAN_PEER).clientIp())
    }

    @ParameterizedTest
    @ValueSource(strings = ["::1", "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1%0", "127.0.0.1", "127.1.2.3"])
    @DisplayName("every form of a loopback peer counts as loopback")
    fun loopbackForms(peer: String) {
        assertEquals(SPOOFED_CF, call(ClientIpSource.CLOUDFLARE, remoteAddress = peer).clientIp())
    }

    @Test
    @DisplayName("trims values and ignores blank headers")
    fun trimsAndSkipsBlank() {
        assertEquals(REAL, call(ClientIpSource.CLOUDFLARE, cfConnectingIp = "  $REAL  ").clientIp())
        assertEquals(LOOPBACK, call(ClientIpSource.CLOUDFLARE, cfConnectingIp = "  ").clientIp())
        assertEquals(LOOPBACK, call(ClientIpSource.NGROK, xForwardedFor = listOf("7.7.7.7, ")).clientIp())
    }

    @Test
    @DisplayName("returns null when there is no socket address")
    fun nullWhenNothing() {
        assertNull(call(ClientIpSource.CLOUDFLARE, remoteAddress = "").clientIp())
    }

    @ParameterizedTest
    @EnumSource(TunnelProviderType::class)
    @DisplayName("with the tunnel off, a listener trusts no header whatever provider is selected")
    fun tunnelOffTrustsNoHeader(provider: TunnelProviderType) {
        val config = ServerConfig(tunnelEnabled = false, tunnelProvider = provider)
        assertEquals(ClientIpSource.SOCKET_PEER, ClientIpSource.of(config))
    }

    @Test
    @DisplayName("each provider maps to what it was measured to deliver")
    fun providersMap() {
        val enabled = ServerConfig(tunnelEnabled = true)

        fun of(provider: TunnelProviderType) = ClientIpSource.of(enabled.copy(tunnelProvider = provider))

        assertEquals(ClientIpSource.CLOUDFLARE, of(TunnelProviderType.CLOUDFLARE))
        assertEquals(ClientIpSource.NGROK, of(TunnelProviderType.NGROK))
        assertEquals(ClientIpSource.SOCKET_PEER, of(TunnelProviderType.ARC_TUNNEL))
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LAN_PEER = "192.168.1.20"
        const val REAL = "203.0.113.10"
        const val SPOOFED_CF = "198.51.100.6"
        const val SPOOFED_XFF = "198.51.100.7"
    }
}
