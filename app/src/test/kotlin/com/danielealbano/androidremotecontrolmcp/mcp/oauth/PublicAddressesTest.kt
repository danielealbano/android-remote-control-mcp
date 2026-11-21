package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.Inet6Address
import java.net.InetAddress

@DisplayName("PublicAddresses")
class PublicAddressesTest {
    private fun isPublic(literal: String) = PublicAddresses.isPublic(InetAddress.getByName(literal))

    @Test
    @DisplayName("refuses the IPv4 special-use blocks, at both ends")
    fun refusesIpv4SpecialUse() {
        listOf(
            "0.0.0.0",
            "0.255.255.255",
            "10.0.0.1",
            "10.255.255.255",
            "100.64.0.1",
            "100.127.255.255",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "192.168.1.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "239.255.255.255",
            "240.0.0.1",
            "255.255.255.255",
        ).forEach { assertFalse(isPublic(it), it) }
    }

    @Test
    @DisplayName("accepts public IPv4 addresses next to the special-use blocks")
    fun acceptsPublicIpv4() {
        listOf(
            "1.1.1.1",
            "9.255.255.255",
            "11.0.0.1",
            "100.63.255.255",
            "100.128.0.1",
            "172.15.255.255",
            "172.32.0.1",
            "160.79.104.1",
            "192.169.0.1",
            "198.17.255.255",
            "198.20.0.1",
            "223.255.255.255",
        ).forEach { assertTrue(isPublic(it), it) }
    }

    @Test
    @DisplayName("allows the benchmarking range, where fake-IP VPNs answer DNS")
    fun allowsFakeIpVpnRange() {
        assertTrue(isPublic("198.18.0.1"))
        assertTrue(isPublic("198.19.255.254"))
    }

    @Test
    @DisplayName("refuses everything outside IPv6 global unicast and its special-use blocks")
    fun refusesIpv6SpecialUse() {
        listOf(
            "::",
            "::1",
            "64:ff9b:1::1",
            "100::1",
            "2001::1",
            "2001:1ff::1",
            "2001:db8::1",
            "2002::1",
            "fc00::1",
            "fdff::1",
            "fe80::1",
            "febf::1",
            "fec0::1",
            "ff02::1",
            "3fff::1",
            "3fff:fff::1",
            "5f00::1",
            "100:0:0:1::1",
            "::1.2.3.4",
            "4000::1",
        ).forEach { assertFalse(isPublic(it), it) }
    }

    @Test
    @DisplayName("accepts public IPv6 addresses")
    fun acceptsPublicIpv6() {
        listOf("2606:4700::1", "2001:200::1", "2a00:1450::1", "3ffe::1", "3fff:1000::1").forEach {
            assertTrue(isPublic(it), it)
        }
    }

    @Test
    @DisplayName("judges IPv4-mapped and NAT64 addresses by the IPv4 address they carry")
    fun judgesEmbeddedIpv4() {
        assertFalse(isPublic("64:ff9b::10.0.0.1"))
        assertFalse(isPublic("64:ff9b::7f00:1"))
        assertTrue(isPublic("64:ff9b::1.1.1.1"))
        // Built by hand: InetAddress.getByName turns a mapped literal into an IPv4 address.
        val mappedPrivate = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 192.toByte(), 168.toByte(), 1, 1)
        val mappedPublic = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 1, 1, 1, 1)
        assertFalse(PublicAddresses.isPublic(Inet6Address.getByAddress(null, mappedPrivate, null)))
        assertTrue(PublicAddresses.isPublic(Inet6Address.getByAddress(null, mappedPublic, null)))
    }
}
