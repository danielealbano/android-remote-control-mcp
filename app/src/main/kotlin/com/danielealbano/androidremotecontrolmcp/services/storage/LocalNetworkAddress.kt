package com.danielealbano.androidremotecontrolmcp.services.storage

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * True when [address] is on the device itself or on its local network, as blocked by "Block Local Network
 * Downloads": loopback, unspecified (0.0.0.0/8 and `::` reach the device itself), private LAN (RFC 1918), link-local
 * (including the 169.254.169.254 cloud metadata address), CGNAT / Tailscale (100.64.0.0/10), IPv6 unique local
 * addresses, and the IPv4-mapped / IPv4-compatible IPv6 forms of the IPv4 ranges.
 */
internal fun isLocalNetworkAddress(address: InetAddress): Boolean =
    when (address) {
        is Inet4Address -> isLocalIpv4(address.address)

        is Inet6Address -> isLocalIpv6(address.address)

        // InetAddress has no other subclasses; refuse rather than guess.
        else -> true
    }

private fun isLocalIpv4(octets: ByteArray): Boolean {
    val first = octets[0].toInt() and BYTE_MASK
    val second = octets[1].toInt() and BYTE_MASK
    return when (first) {
        // 0.0.0.0/8 (this host) and 127.0.0.0/8 (loopback)
        0, LOOPBACK_FIRST_OCTET -> true

        // 10.0.0.0/8
        PRIVATE_10_FIRST_OCTET -> true

        // 172.16.0.0/12
        PRIVATE_172_FIRST_OCTET -> second in PRIVATE_172_SECOND_OCTETS

        // 192.168.0.0/16
        PRIVATE_192_FIRST_OCTET -> second == PRIVATE_192_SECOND_OCTET

        // 169.254.0.0/16 (link-local, cloud metadata)
        LINK_LOCAL_FIRST_OCTET -> second == LINK_LOCAL_SECOND_OCTET

        // 100.64.0.0/10 (CGNAT, Tailscale)
        CGNAT_FIRST_OCTET -> second in CGNAT_SECOND_OCTETS

        else -> false
    }
}

private fun isLocalIpv6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and BYTE_MASK
    val second = bytes[1].toInt() and BYTE_MASK
    return when {
        // fe80::/10 (link-local)
        isIpv6LinkLocal(first, second) -> {
            true
        }

        // fc00::/7 (unique local)
        (first and IPV6_ULA_MASK) == IPV6_ULA_BITS -> {
            true
        }

        // ::ffff:a.b.c.d (IPv4-mapped)
        isZero(bytes, 0, IPV4_MAPPED_PREFIX_END) && isMappedMarker(bytes) -> {
            isLocalIpv4(bytes.copyOfRange(IPV4_EMBEDDED_START, IPV6_LENGTH))
        }

        // :: (unspecified), ::1 (loopback) and ::a.b.c.d (IPv4-compatible): the embedded IPv4 of :: is 0.0.0.0 and of
        // ::1 is 0.0.0.1, both inside 0.0.0.0/8, so one check covers all three.
        isZero(bytes, 0, IPV4_EMBEDDED_START) -> {
            isLocalIpv4(bytes.copyOfRange(IPV4_EMBEDDED_START, IPV6_LENGTH))
        }

        else -> {
            false
        }
    }
}

private fun isIpv6LinkLocal(
    first: Int,
    second: Int,
): Boolean =
    first == IPV6_LINK_LOCAL_FIRST_BYTE &&
        (second and IPV6_LINK_LOCAL_SECOND_MASK) == IPV6_LINK_LOCAL_SECOND_BITS

private fun isZero(
    bytes: ByteArray,
    from: Int,
    until: Int,
): Boolean = (from until until).all { bytes[it] == 0.toByte() }

private fun isMappedMarker(bytes: ByteArray): Boolean =
    bytes[IPV4_MAPPED_PREFIX_END] == MAPPED_MARKER && bytes[IPV4_MAPPED_PREFIX_END + 1] == MAPPED_MARKER

private const val BYTE_MASK = 0xff
private const val LOOPBACK_FIRST_OCTET = 127
private const val PRIVATE_10_FIRST_OCTET = 10
private const val PRIVATE_172_FIRST_OCTET = 172
private val PRIVATE_172_SECOND_OCTETS = 16..31
private const val PRIVATE_192_FIRST_OCTET = 192
private const val PRIVATE_192_SECOND_OCTET = 168
private const val LINK_LOCAL_FIRST_OCTET = 169
private const val LINK_LOCAL_SECOND_OCTET = 254
private const val CGNAT_FIRST_OCTET = 100
private val CGNAT_SECOND_OCTETS = 64..127
private const val IPV6_LINK_LOCAL_FIRST_BYTE = 0xfe
private const val IPV6_LINK_LOCAL_SECOND_MASK = 0xc0
private const val IPV6_LINK_LOCAL_SECOND_BITS = 0x80
private const val IPV6_ULA_MASK = 0xfe
private const val IPV6_ULA_BITS = 0xfc
private const val IPV4_MAPPED_PREFIX_END = 10
private const val IPV4_EMBEDDED_START = 12
private const val IPV6_LENGTH = 16
private const val MAPPED_MARKER = 0xff.toByte()
