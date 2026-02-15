package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves the cloudflared binary from the app's native library directory.
 *
 * The binary is packaged as `libcloudflared.so` in `jniLibs/<abi>/` and extracted
 * by the Android package manager to `nativeLibraryDir` at install time (requires
 * `useLegacyPackaging = true`). The native library directory has execute permissions,
 * allowing the binary to be run as a child process via `ProcessBuilder`.
 */
class AndroidCloudflareBinaryResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CloudflaredBinaryResolver {
        override fun resolve(): String? {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(nativeLibDir, LIBRARY_NAME)

            return when {
                !binaryFile.exists() -> {
                    Log.e(TAG, "cloudflared binary not found at: ${binaryFile.absolutePath}")
                    null
                }
                !binaryFile.canExecute() -> {
                    Log.e(TAG, "cloudflared binary is not executable: ${binaryFile.absolutePath}")
                    null
                }
                else -> binaryFile.absolutePath
            }
        }

        companion object {
            private const val TAG = "MCP:CloudflaredResolver"
            internal const val LIBRARY_NAME = "libcloudflared.so"
        }
    }
