package com.danielealbano.androidremotecontrolmcp.services.storage

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
