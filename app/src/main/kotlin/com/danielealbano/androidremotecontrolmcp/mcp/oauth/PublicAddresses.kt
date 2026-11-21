package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Tells globally reachable addresses from the special-use ones of RFC 6890 and its IANA registries, which the OAuth
 * server's downloads must never reach (draft-ietf-oauth-client-id-metadata-document section 10.7): unspecified,
 * loopback, private, shared (CGNAT), link-local, documentation, benchmarking, protocol assignments, multicast and
 * reserved. An IPv6 address carrying an IPv4 one (mapped, NAT64 well-known prefix) is judged by that IPv4 address.
 */
object PublicAddresses {
    private class Block(
        val prefix: ByteArray,
        val bits: Int,
    ) {
        fun contains(address: ByteArray): Boolean {
            val whole = bits / BITS_PER_BYTE
            val rest = bits % BITS_PER_BYTE
            val mask = (BYTE_MASK shl (BITS_PER_BYTE - rest)) and BYTE_MASK
            return address.size == prefix.size &&
                (0 until whole).all { address[it] == prefix[it] } &&
                (rest == 0 || (address[whole].toInt() and mask) == (prefix[whole].toInt() and mask))
        }
    }

    private fun block(cidr: String): Block {
        val (address, bits) = cidr.split('/')
        return Block(InetAddress.getByName(address).address, bits.toInt())
    }

    private val IPV4_SPECIAL =
        listOf(
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "192.88.99.0/24",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "224.0.0.0/4",
            "240.0.0.0/4",
        ).map(::block)

    private val IPV6_SPECIAL =
        listOf(
            "::/127",
            "64:ff9b:1::/48",
            "100::/64",
            "2001::/23",
            "2001:db8::/32",
            "2002::/16",
            "fc00::/7",
            "fe80::/10",
            "fec0::/10",
            "ff00::/8",
        ).map(::block)

    /**
     * Prefixes whose last 32 bits are an IPv4 address: IPv4-mapped (`::ffff:0:0/96`, built by hand because
     * [InetAddress.getByName] turns it into an IPv4 address) and NAT64 (`64:ff9b::/96`).
     */
    private val IPV6_EMBEDDING_IPV4 =
        listOf(
            Block(ByteArray(IPV6_BYTES).apply { fill(ALL_ONES, MAPPED_MARKER, MAPPED_MARKER + 2) }, MAPPED_BITS),
            block("64:ff9b::/96"),
        )

    /** True when [address] is globally reachable, so a download may connect to it. */
    fun isPublic(address: InetAddress): Boolean {
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                IPV4_SPECIAL.none { it.contains(bytes) }
            }

            is Inet6Address -> {
                if (IPV6_EMBEDDING_IPV4.any { it.contains(bytes) }) {
                    val ipv4 = bytes.copyOfRange(bytes.size - IPV4_BYTES, bytes.size)
                    IPV4_SPECIAL.none { it.contains(ipv4) }
                } else {
                    IPV6_SPECIAL.none { it.contains(bytes) }
                }
            }

            else -> {
                false
            }
        }
    }

    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val MAPPED_MARKER = 10
    private const val MAPPED_BITS = 96
    private const val ALL_ONES: Byte = -1
}
