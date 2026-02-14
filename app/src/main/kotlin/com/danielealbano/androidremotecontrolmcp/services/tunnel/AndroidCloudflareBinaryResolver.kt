package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves the cloudflared binary by extracting it from the APK's assets directory.
 *
 * The binary is bundled as `cloudflared-<abi>` in `assets/` and extracted to the
 * app's files directory on each resolution. This avoids relying on `jniLibs`
 * extraction which requires `useLegacyPackaging = true` (incompatible with 16KB
 * page alignment).
 */
class AndroidCloudflareBinaryResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CloudflaredBinaryResolver {
        override fun resolve(): String? {
            val assetName = resolveAssetName() ?: return null
            val targetFile = File(context.filesDir, EXTRACTED_BINARY_NAME)

            return try {
                extractAsset(assetName, targetFile)
                targetFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract cloudflared binary from assets", e)
                null
            }
        }

        private fun resolveAssetName(): String? {
            val availableAssets =
                try {
                    context.assets.list("")?.toSet() ?: emptySet()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to list assets", e)
                    return null
                }

            for (abi in Build.SUPPORTED_ABIS) {
                val candidate = "$ASSET_PREFIX$abi"
                if (candidate in availableAssets) {
                    return candidate
                }
            }

            Log.e(
                TAG,
                "No cloudflared asset found for ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
            )
            return null
        }

        private fun extractAsset(
            assetName: String,
            targetFile: File,
        ) {
            context.assets.open(assetName).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setExecutable(true, true)
        }

        companion object {
            private const val TAG = "MCP:CloudflaredResolver"
            internal const val ASSET_PREFIX = "cloudflared-"
            internal const val EXTRACTED_BINARY_NAME = "cloudflared"
        }
    }
