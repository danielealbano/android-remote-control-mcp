package com.danielealbano.androidremotecontrolmcp.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * Utility functions for network operations.
 */
object NetworkUtils {

    private const val TAG = "MCP:NetworkUtils"

    /**
     * Returns the device's primary IPv4 address, or `null` if unavailable.
     *
     * Uses [ConnectivityManager] to query the active network's link properties.
     *
     * @param context Application context for accessing system services.
     * @return The device's IPv4 address as a string, or `null`.
     */
    fun getDeviceIpAddress(context: Context): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: return null

        return linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    /**
     * Checks whether a TCP port is currently available for binding.
     *
     * Attempts to open a [ServerSocket] on the given port. If it succeeds,
     * the port is available. If it throws, the port is in use.
     *
     * @param port The TCP port to check (1-65535).
     * @return `true` if the port is available, `false` otherwise.
     */
    fun isPortAvailable(port: Int): Boolean =
        try {
            ServerSocket(port).use { true }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }

    /**
     * Lists all available network interfaces with their IPv4 addresses.
     *
     * @return A list of [NetworkInterfaceInfo] for each interface with an IPv4 address.
     */
    fun getNetworkInterfaces(): List<NetworkInterfaceInfo> {
        val result = mutableListOf<NetworkInterfaceInfo>()

        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result

        for (networkInterface in interfaces.asSequence()) {
            for (address in networkInterface.inetAddresses.asSequence()) {
                if (address is Inet4Address) {
                    result.add(
                        NetworkInterfaceInfo(
                            name = networkInterface.displayName,
                            address = address.hostAddress ?: continue,
                            isLoopback = address.isLoopbackAddress,
                        ),
                    )
                }
            }
        }

        return result
    }
}

/**
 * Information about a network interface.
 *
 * @property name The display name of the interface (e.g., "wlan0", "lo").
 * @property address The IPv4 address assigned to this interface.
 * @property isLoopback Whether this is a loopback interface.
 */
data class NetworkInterfaceInfo(
    val name: String,
    val address: String,
    val isLoopback: Boolean,
)
