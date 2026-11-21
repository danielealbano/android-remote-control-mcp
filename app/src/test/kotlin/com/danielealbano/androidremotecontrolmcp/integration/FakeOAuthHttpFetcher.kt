package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.FetchedBody
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthHttpFetcher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [OAuthHttpFetcher] for tests: serves [bodies] by exact URL (null for anything else, like a failed
 * download) and records every URL asked for in [requested].
 */
class FakeOAuthHttpFetcher(
    private val bodies: Map<String, FetchedBody> = emptyMap(),
) : OAuthHttpFetcher {
    val requested = CopyOnWriteArrayList<String>()

    override suspend fun get(
        url: String,
        accept: String,
        maxBytes: Int,
    ): FetchedBody? {
        requested += url
        return bodies[url]?.takeIf { it.bytes.size <= maxBytes }
    }

    companion object {
        /** A body served as `application/json`. */
        fun json(body: String): FetchedBody = FetchedBody("application/json", body.encodeToByteArray())
    }
}
