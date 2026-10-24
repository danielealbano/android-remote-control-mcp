package com.danielealbano.androidremotecontrolmcp.services.storage

import io.mockk.coEvery
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A canned [DownloadResponse] for tests of the download paths; records whether it was closed. */
internal class FakeDownloadResponse(
    override val code: Int,
    override val contentLength: Long = -1L,
    override val body: InputStream = ByteArrayInputStream(ByteArray(0)),
) : DownloadResponse {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}

/**
 * Stubs [DownloadHttpClient.download] on this mock to hand [response] to the caller's `onResponse` and to close it
 * afterwards, as the real client does.
 */
internal fun DownloadHttpClient.stubDownload(response: DownloadResponse) {
    coEvery { download<Any?>(any(), any(), any()) } coAnswers {
        val onResponse = thirdArg<suspend (DownloadResponse) -> Any?>()
        response.use { onResponse(it) }
    }
}

/**
 * A body that serves one byte per read and runs [onSecondRead] during its second read, standing in for a request
 * cancelled while its transfer runs; it ends after [MAX_CANCELLING_BODY_READS] reads if nothing stops the copy.
 */
internal fun cancellingBody(onSecondRead: () -> Unit): InputStream =
    object : InputStream() {
        private var reads = 0

        override fun read(): Int = throw UnsupportedOperationException("read(ByteArray, Int, Int) only")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            reads++
            if (reads == 2) onSecondRead()
            if (reads > MAX_CANCELLING_BODY_READS) return -1
            b[off] = 1
            return 1
        }
    }

private const val MAX_CANCELLING_BODY_READS = 10
