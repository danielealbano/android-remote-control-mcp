package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.os.SystemClock
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/** What the public status page of the free E2E tunnel reports when it is not operational. */
data class ArcTunnelServiceProblem(
    val state: String,
    /** The services not operational; empty when the page did not say, which still is a problem to report. */
    val affected: List<String>,
)

/** The state of the free E2E tunnel service, from its public status page. */
interface ArcTunnelServiceStatus {
    /**
     * The reported problem, or null when there is none or anything prevents knowing. A process asks the page at most
     * once per [ArcTunnelStatusPage.CHECK_INTERVAL_MS]; until then the last answer stands. Never throws, except to
     * cancel.
     */
    suspend fun currentProblem(): ArcTunnelServiceProblem?
}

/**
 * Reads the JSON behind the status page (Better Stack, no login). The format belongs to Better Stack and can change, so
 * nothing here may fail for the user: the overall state decides, and without one there is no problem to report.
 */
object ArcTunnelStatusPage {
    const val PAGE_URL = "https://status.arc-tunnel.io/"
    const val JSON_URL = "https://status.arc-tunnel.io/index.json"
    const val CHECK_INTERVAL_MS = 1_800_000L
    private const val OPERATIONAL = "operational"
    private const val NOT_MONITORED = "not_monitored"
    private const val RESOURCE_TYPE = "status_page_resource"

    fun parseProblem(body: String): ArcTunnelServiceProblem? =
        runCatching {
            val root = ArcTunnelJson.parseToJsonElement(body) as JsonObject
            val attributes = (root["data"] as JsonObject)["attributes"] as JsonObject
            val state = string(attributes["aggregate_state"])
            if (state.isNullOrBlank() || state == OPERATIONAL) {
                null
            } else {
                ArcTunnelServiceProblem(state.replace('_', ' '), affectedServices(root))
            }
        }.getOrNull()

    /** Best effort: the overall state stands on its own, so failing to read the details only loses the details. */
    private fun affectedServices(root: JsonObject): List<String> =
        runCatching {
            root["included"]
                .let { it as JsonArray }
                .mapNotNull { affectedService(it) }
        }.getOrDefault(emptyList())

    private fun affectedService(element: JsonElement): String? {
        val resource = element as? JsonObject
        val attributes = resource?.get("attributes") as? JsonObject
        val status = string(attributes?.get("status"))
        val isAffected =
            string(resource?.get("type")) == RESOURCE_TYPE &&
                !status.isNullOrBlank() &&
                status != OPERATIONAL &&
                status != NOT_MONITORED
        return string(attributes?.get("public_name"))?.takeIf { isAffected && it.isNotBlank() }
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Singleton
class ArcTunnelServiceStatusImpl internal constructor(
    private val url: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long,
) : ArcTunnelServiceStatus {
    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(ArcTunnelStatusPage.JSON_URL, ioDispatcher, SystemClock::elapsedRealtime)

    private class Check(
        val atMs: Long,
        val problem: ArcTunnelServiceProblem?,
    )

    private val client by lazy { OkHttpClient.Builder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build() }
    private val mutex = Mutex()

    /** Lives as long as the process: a new process asks again. */
    private var lastCheck: Check? = null

    override suspend fun currentProblem(): ArcTunnelServiceProblem? =
        withContext(ioDispatcher) {
            mutex.withLock {
                val last = lastCheck
                if (last != null && nowMs() - last.atMs < ArcTunnelStatusPage.CHECK_INTERVAL_MS) {
                    last.problem
                } else {
                    // Before asking, so a request cancelled half way counts towards the limit too.
                    lastCheck = Check(nowMs(), last?.problem)
                    check().also { lastCheck = Check(nowMs(), it) }
                }
            }
        }

    private suspend fun check(): ArcTunnelServiceProblem? =
        runCatching { ArcTunnelStatusPage.parseProblem(fetch()) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.d(TAG, "Tunnel status check failed: ${it.javaClass.simpleName}")
            }.getOrNull()

    private suspend fun fetch(): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resumeWith(runCatching { response.use { bodyOf(it) } })
                    }
                },
            )
        }

    private fun bodyOf(response: Response): String {
        check(response.isSuccessful) { "HTTP ${response.code}" }
        return response.peekBody(MAX_BODY_BYTES).string()
    }

    private companion object {
        const val TAG = "MCP:ArcTunnelStatus"
        const val CALL_TIMEOUT_S = 10L
        const val MAX_BODY_BYTES = 1_048_576L
    }
}
