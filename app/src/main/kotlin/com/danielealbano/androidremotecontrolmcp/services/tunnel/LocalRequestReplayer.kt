package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Base64

/**
 * Replays a relay request frame against the local MCP server (`http://127.0.0.1:<port>`) and packs the
 * answer into a response frame. Failures never propagate: they become 4xx/5xx response frames (generic JSON
 * messages — loopback details stay in logcat) so the relay can still answer its HTTP client.
 */
internal class LocalRequestReplayer(
    private val client: HttpClient,
    private val timeoutMs: Long = REPLAY_TIMEOUT_MS,
    private val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
) {
    suspend fun replay(
        frame: RelayRequestFrame,
        localPort: Int,
    ): RelayResponseFrame =
        if (isOriginForm(frame.path)) {
            replayValidated(frame, localPort)
        } else {
            Logger.w(TAG, "Relay request rejected: path is not origin-form (${frame.method})")
            errorFrame(frame.id, HttpStatusCode.BadRequest, "invalid request path")
        }

    /** Builds a JSON error frame; also used by the session for `503 device busy`. */
    fun errorFrame(
        id: String,
        status: HttpStatusCode,
        message: String,
    ): RelayResponseFrame =
        RelayResponseFrame(
            id = id,
            status = status.value,
            headers = mapOf("Content-Type" to listOf(ContentType.Application.Json.toString())),
            body = Base64.getEncoder().encodeToString("""{"error":${JsonPrimitive(message)}}""".toByteArray()),
        )

    private suspend fun replayValidated(
        frame: RelayRequestFrame,
        localPort: Int,
    ): RelayResponseFrame =
        try {
            val response =
                client.request("http://127.0.0.1:$localPort${frame.path}") {
                    applyFrame(frame)
                    timeout {
                        requestTimeoutMillis = timeoutMs
                        socketTimeoutMillis = timeoutMs
                    }
                }
            packResponse(frame.id, response)
        } catch (e: HttpRequestTimeoutException) {
            Logger.w(TAG, "Local replay timed out: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.GatewayTimeout, "local MCP server did not answer in time")
        } catch (e: IOException) {
            Logger.w(TAG, "Local replay failed: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.BadGateway, "local MCP server unreachable")
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "Local replay rejected: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.BadRequest, "invalid relayed request")
        } catch (e: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            Logger.w(TAG, "Local replay rejected: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.BadRequest, "invalid relayed request")
        }

    private suspend fun packResponse(
        id: String,
        response: HttpResponse,
    ): RelayResponseFrame {
        val channel = response.bodyAsChannel()
        val bytes = channel.readRemaining(maxResponseBytes + 1).readByteArray()
        if (bytes.size > maxResponseBytes) {
            channel.cancel(null)
            Logger.w(TAG, "Local response dropped: body exceeds $maxResponseBytes bytes")
            return errorFrame(id, HttpStatusCode.BadGateway, "response too large (limit $maxResponseBytes bytes)")
        }
        return RelayResponseFrame(
            id = id,
            status = response.status.value,
            headers = forwardableResponseHeaders(response.headers),
            body = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun HttpRequestBuilder.applyFrame(frame: RelayRequestFrame) {
        method = HttpMethod.parse(frame.method.uppercase())
        frame.headers.forEach { (name, values) ->
            if (name.lowercase() !in SKIPPED_REQUEST_HEADERS) {
                values.forEach { headers.append(name, it) }
            }
        }
        val bytes = Base64.getDecoder().decode(frame.body)
        if (bytes.isNotEmpty()) {
            setBody(ByteArrayContent(bytes, contentType = frame.contentType()))
        }
    }

    private fun RelayRequestFrame.contentType(): ContentType =
        headers.entries
            .firstOrNull { it.key.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream

    private fun forwardableResponseHeaders(headers: Headers): Map<String, List<String>> =
        headers
            .entries()
            .filterNot { it.key.lowercase() in SKIPPED_RESPONSE_HEADERS }
            .associate { it.key to it.value }

    companion object {
        private const val TAG = "MCP:RelayReplay"
        private const val CONTENT_TYPE_HEADER = "content-type"
        private const val CAPABILITY_LINK_PREFIX = "/s/"

        /** Per-request budget; shorter than the relay's 120 s so the device's own 504 frame reaches the client. */
        const val REPLAY_TIMEOUT_MS = 100_000L

        /** Largest response body relayed; above it the device answers 502 (OkHttp drops frames past 16 MiB). */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /** Hop-by-hop or engine-managed request headers never copied onto the local request. */
        private val SKIPPED_REQUEST_HEADERS =
            setOf(
                "host",
                "content-length",
                "content-type",
                "transfer-encoding",
                "connection",
                "keep-alive",
                "upgrade",
                "te",
                "trailer",
                "expect",
                "proxy-authorization",
                "proxy-authenticate",
                "accept-encoding",
            )

        /** Headers the relay recomputes itself. */
        private val SKIPPED_RESPONSE_HEADERS =
            setOf(
                "content-length",
                "transfer-encoding",
                "connection",
                "keep-alive",
            )

        /** Origin-form only: a leading `/` and not `//` (which `URI` would read as an authority). */
        internal fun isOriginForm(path: String): Boolean = path.startsWith("/") && !path.startsWith("//")

        /** Log-safe path: no query string, capability links redacted. */
        internal fun sanitizedPath(path: String): String {
            val noQuery = path.substringBefore('?')
            return if (noQuery.startsWith(CAPABILITY_LINK_PREFIX)) "$CAPABILITY_LINK_PREFIX<redacted>" else noQuery
        }
    }
}
