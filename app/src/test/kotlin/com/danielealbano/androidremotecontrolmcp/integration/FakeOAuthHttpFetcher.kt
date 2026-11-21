package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.FetchResult
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.FetchedBody
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthHttpFetcher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [OAuthHttpFetcher] for tests: serves [bodies] by exact URL (a failed download for anything else, or for a
 * body over the limit) and records every URL asked for in [requested].
 */
class FakeOAuthHttpFetcher(
    private val bodies: Map<String, FetchedBody> = emptyMap(),
) : OAuthHttpFetcher {
    val requested = CopyOnWriteArrayList<String>()

    override suspend fun get(
        url: String,
        accept: String,
        maxBytes: Int,
    ): FetchResult {
        requested += url
        val body = bodies[url]
        return when {
            body == null -> FetchResult.Failed(NOT_SERVED)
            body.bytes.size > maxBytes -> FetchResult.Failed("larger than $maxBytes bytes")
            else -> FetchResult.Fetched(body)
        }
    }

    companion object {
        /** The reason of a download this fake does not serve. */
        const val NOT_SERVED = "not served by the test"

        /** A body served as `application/json`. */
        fun json(body: String): FetchedBody = FetchedBody("application/json", body.encodeToByteArray())
    }
}
