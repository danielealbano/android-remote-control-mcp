package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves the cloudflared binary from the APK's native library directory.
 *
 * The binary is named `libcloudflared.so` and placed in `jniLibs/<abi>/`
 * so that Android extracts it with execute permissions.
 */
class AndroidCloudflareBinaryResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CloudflaredBinaryResolver {
        override fun resolve(): String? {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
            return if (binary.exists() && binary.canExecute()) binary.absolutePath else null
        }
    }
