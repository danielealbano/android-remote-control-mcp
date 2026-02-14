package com.danielealbano.androidremotecontrolmcp.services.tunnel

/**
 * Resolves the filesystem path to the cloudflared binary.
 *
 * Production implementation extracts the binary from APK assets into the
 * app's files directory. Test implementations can point to a host-native binary.
 */
interface CloudflaredBinaryResolver {
    /** Returns the absolute path to the cloudflared binary, or null if not found. */
    fun resolve(): String?
}
