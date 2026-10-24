package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelHosts / ArcTunnelEnv / ArcTunnelEdgeOverride")
class ArcTunnelHostsTest {
    @Test
    fun `derives enroll connect and d hosts`() {
        val hosts = ArcTunnelHosts("arc-tunnel.io")

        assertEquals("enroll.arc-tunnel.io", hosts.enrollHost)
        assertEquals("connect.arc-tunnel.io", hosts.controlHost)
        assertEquals("d.arc-tunnel.io", hosts.tunnelDomain)
        assertEquals("https://abc.d.arc-tunnel.io", hosts.publicUrl("abc"))
    }

    @Test
    fun `envKey is stable and base-specific`() {
        val first = ArcTunnelHosts("arc-tunnel.io").envKey

        assertEquals(first, ArcTunnelHosts("arc-tunnel.io").envKey)
        assertNotEquals(first, ArcTunnelHosts("tunnel.example.org").envKey)
        assertEquals(13, first.length)
        assertTrue(first.startsWith("e"))
    }

    @Test
    fun `isValidBaseAddress accepts and rejects`() {
        val label63 = "a".repeat(63)
        // 63 + 1 + 63 + 1 + 63 + 1 + 61 = 253 characters, every label within 63.
        val name253 = "$label63.$label63.$label63.${"b".repeat(61)}"
        val name254 = "$label63.$label63.$label63.${"b".repeat(62)}"

        listOf("arc-tunnel.io", "t.example.org", name253, "$label63.example.org").forEach {
            assertTrue(ArcTunnelHosts.isValidBaseAddress(it), it)
        }
        listOf(
            "",
            "localhost",
            "Arc-Tunnel.io",
            "a..b",
            "-a.b",
            "https://x.y",
            "x.y:443",
            "x.y.",
            name254,
            "${"a".repeat(64)}.example.org",
        ).forEach {
            assertFalse(ArcTunnelHosts.isValidBaseAddress(it), it)
        }
        assertEquals(253, name253.length)
        assertEquals(254, name254.length)
    }

    @Test
    fun `edge override parses host and port`() {
        assertEquals(ArcTunnelEdgeOverride("10.0.0.1", 18443), ArcTunnelEdgeOverride.parse("10.0.0.1:18443"))
        assertEquals(ArcTunnelEdgeOverride("[::1]", 443), ArcTunnelEdgeOverride.parse("[::1]:443"))
        assertNull(ArcTunnelEdgeOverride.parse("x"))
        assertNull(ArcTunnelEdgeOverride.parse("x:0"))
        assertNull(ArcTunnelEdgeOverride.parse("x:70000"))
    }

    @Test
    fun `env from config honours overrides only in debug`() {
        val config =
            ServerConfig(
                arcTunnelBaseAddress = "tunnel.example.org",
                arcTunnelEdgeOverride = "10.0.0.1:18443",
                arcTunnelTrustedCaPem = "pem",
            )

        val debug = ArcTunnelEnv.from(config, debug = true)
        val release = ArcTunnelEnv.from(config, debug = false)

        assertEquals(ArcTunnelEnv("tunnel.example.org", "10.0.0.1:18443", "pem"), debug)
        assertEquals(ArcTunnelEnv("tunnel.example.org"), release)
        assertEquals("connect.tunnel.example.org", release.hosts.controlHost)
    }
}
