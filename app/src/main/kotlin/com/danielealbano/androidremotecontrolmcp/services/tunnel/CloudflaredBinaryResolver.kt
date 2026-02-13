package com.danielealbano.androidremotecontrolmcp.services.tunnel

/**
 * Resolves the filesystem path to the cloudflared binary.
 *
 * Production implementation reads from [android.content.Context.getApplicationInfo]
 * nativeLibraryDir. Test implementations can point to a host-native binary.
 */
interface CloudflaredBinaryResolver {
    /** Returns the absolute path to the cloudflared binary, or null if not found. */
    fun resolve(): String?
}
