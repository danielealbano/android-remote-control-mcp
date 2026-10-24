package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import java.net.URL

/**
 * Streams an HTTP(S) download into a pending MediaStore entry, clearing IS_PENDING on
 * success and deleting the entry on any failure.
 */
internal class MediaStoreDownloader(
    private val context: Context,
    private val downloadHttpClient: DownloadHttpClient,
) {
    @Suppress("TooGenericExceptionCaught")
    fun downloadToPendingUri(
        insertUri: Uri,
        parsedUrl: URL,
        url: String,
        config: ServerConfig,
        logContext: String,
    ): Long {
        try {
            downloadHttpClient.open(parsedUrl, config).use { response ->
                validateResponse(response, url, config)
                val totalBytesWritten = streamToDestination(response, insertUri, config)
                markDownloadComplete(insertUri)
                Log.i(TAG, "Downloaded $totalBytesWritten bytes from $url to $logContext")
                return totalBytesWritten
            }
        } catch (e: McpToolException) {
            context.contentResolver.delete(insertUri, null, null)
            throw e
        } catch (e: Exception) {
            context.contentResolver.delete(insertUri, null, null)
            throw McpToolException.ActionFailed(
                "Download failed: ${e.message ?: "Unknown error"}",
                e,
            )
        }
    }

    private fun validateResponse(
        response: DownloadResponse,
        url: String,
        config: ServerConfig,
    ) {
        val responseCode = response.code
        if (responseCode !in HTTP_SUCCESS_RANGE) {
            throw McpToolException.ActionFailed(
                "Download failed with HTTP status $responseCode for URL: $url",
            )
        }

        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        val contentLength = response.contentLength
        if (contentLength > 0 && contentLength > limitBytes) {
            throw McpToolException.ActionFailed(
                "Server reports file size of $contentLength bytes, exceeds limit of " +
                    "${config.fileSizeLimitMb} MB.",
            )
        }
    }

    private fun streamToDestination(
        response: DownloadResponse,
        insertUri: Uri,
        config: ServerConfig,
    ): Long {
        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        var totalBytesWritten = 0L
        context.contentResolver.openOutputStream(insertUri, "wt")?.use { outputStream ->
            response.body.use { inputStream ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesWritten += bytesRead
                    if (totalBytesWritten > limitBytes) {
                        throw McpToolException.ActionFailed(
                            "Download exceeds the configured file size limit of " +
                                "${config.fileSizeLimitMb} MB.",
                        )
                    }
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        } ?: throw McpToolException.ActionFailed(
            "Failed to open download destination for writing",
        )
        return totalBytesWritten
    }

    private fun markDownloadComplete(insertUri: Uri) {
        // Clear IS_PENDING on success
        val updateValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        context.contentResolver.update(insertUri, updateValues, null, null)
    }

    companion object {
        private const val TAG = "MCP:MediaStoreDownload"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}
