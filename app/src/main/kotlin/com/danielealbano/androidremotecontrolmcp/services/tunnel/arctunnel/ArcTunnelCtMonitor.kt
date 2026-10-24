package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@Serializable
data class ArcTunnelCtIssuance(
    val id: String,
    @SerialName("pubkey_sha256") val pubkeySha256: String = "",
    @SerialName("not_after") val notAfter: String = "",
)

/** Pure trigger rule: 1 % of requests, ≥ 30 min apart. */
object ArcTunnelCtTrigger {
    const val PERCENT = 1
    const val ROLL_RANGE = 100
    const val MIN_INTERVAL_MS = 30L * 60 * 1000

    fun shouldCheck(
        enabled: Boolean,
        roll: Int,
        nowMs: Long,
        lastCheckMs: Long,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean = enabled && roll < PERCENT && nowMs - lastCheckMs >= minIntervalMs
}

/**
 * Cert Spotter keyless lookup of the tunnel name, compared with the phone's own TLS public key. One
 * instance per provider session; checks run on [scope] (the provider's), one at a time, and the time of the
 * last check is persisted so the 30-minute spacing survives a restart.
 */
class ArcTunnelCtMonitor(
    private val deps: Deps,
    private val scope: CoroutineScope,
    private val enabled: Boolean,
    private val options: Options = Options(),
) {
    class Deps(
        val identity: ArcTunnelIdentity,
        val store: ArcTunnelIdentityStore,
        val keys: ArcTunnelKeyStore,
        val alerts: ArcTunnelCtAlerts,
    )

    /** Test seams: the API base, the random source and the minimum interval. */
    data class Options(
        val baseUrl: String = API_BASE,
        val random: Random = Random.Default,
        val minIntervalMs: Long = ArcTunnelCtTrigger.MIN_INTERVAL_MS,
    )

    private val inFlight = AtomicBoolean(false)
    private val lastCheckMs = AtomicLong(deps.store.load()?.ctLastCheckMs ?: 0L)
    private val client = OkHttpClient.Builder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Evicts the idle connections of the lookup client (session end). */
    fun close() {
        client.connectionPool.evictAll()
    }

    /** Re-shows an alert the user has not dismissed yet (process restart). */
    fun restoreAlert() {
        val saved = deps.store.load()
        val pubkey = saved?.ctAlertPubkey.orEmpty()
        if (pubkey.isNotEmpty()) deps.alerts.restore(ArcTunnelCtAlert(deps.identity.fqdn(), pubkey))
    }

    /** Called on every request through the tunnel listener; cheap unless a check is due. */
    fun onRequest() {
        val roll = options.random.nextInt(ArcTunnelCtTrigger.ROLL_RANGE)
        val now = System.currentTimeMillis()
        if (!ArcTunnelCtTrigger.shouldCheck(enabled, roll, now, lastCheckMs.get(), options.minIntervalMs)) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                check()
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** One lookup from the saved cursor; flags every issuance whose key is not ours; records the check time. */
    suspend fun check() {
        val saved = deps.store.load() ?: return
        val now = System.currentTimeMillis()
        val ownKey = ArcTunnelPem.publicKeySha256(deps.keys.publicKey(saved.tlsAlias))
        val issuances = fetchOrEmpty(deps.identity.fqdn(), saved.ctLastId)
        val known = saved.ctFlagged.toMutableList()
        val newFlagged = mutableListOf<String>()
        issuances
            .filter { it.pubkeySha256.isNotEmpty() && it.pubkeySha256 != ownKey && it.pubkeySha256 !in known }
            .forEach {
                known += it.pubkeySha256
                newFlagged += it.pubkeySha256
                Log.w(TAG, "certificate for ${deps.identity.fqdn()} issued to another key (ct id ${it.id})")
                deps.alerts.raise(ArcTunnelCtAlert(deps.identity.fqdn(), it.pubkeySha256))
            }
        // Merged into the current record: a dismissal made during the lookup is not undone.
        deps.store.mergeCtCheck(issuances.lastOrNull()?.id ?: saved.ctLastId, newFlagged, newFlagged.lastOrNull(), now)
        lastCheckMs.set(now)
    }

    /** A failed or malformed lookup is logged and yields no issuances, so the cursor is left untouched. */
    private suspend fun fetchOrEmpty(
        fqdn: String,
        after: String,
    ): List<ArcTunnelCtIssuance> =
        try {
            fetch(fqdn, after)
        } catch (e: IOException) {
            Log.w(TAG, "certificate transparency lookup failed", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "certificate transparency answer not understood: ${e.javaClass.simpleName}")
            emptyList()
        }

    private suspend fun fetch(
        fqdn: String,
        after: String,
    ): List<ArcTunnelCtIssuance> {
        val url =
            options.baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("v1/issuances")
                .addQueryParameter("domain", fqdn)
                .addQueryParameter("match_wildcards", "true")
                .addQueryParameter("expand", "dns_names")
                .apply { if (after.isNotEmpty()) addQueryParameter("after", after) }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        val (code, body) = ArcTunnelHttp.await(client.newCall(request))
        if (code != HttpURLConnection.HTTP_OK) throw IOException("cert spotter answered $code")
        return ArcTunnelJson.decodeFromString(ListSerializer(ArcTunnelCtIssuance.serializer()), body)
    }

    companion object {
        private const val TAG = "MCP:ArcTunnelCt"
        private const val API_BASE = "https://api.certspotter.com/"
        private const val TIMEOUT_S = 20L
    }
}
