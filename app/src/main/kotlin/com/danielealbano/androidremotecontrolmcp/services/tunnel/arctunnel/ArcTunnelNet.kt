package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP clients and TLS contexts of the tunnel client. With [edgeOverride] every service host resolves to the
 * override address and carries its port in the URL (SNI and `Host` stay the real names); [trustedCaPem] (empty
 * = system CAs) is the trust anchor set. One shared [OkHttpClient] backs every derived client (one pool);
 * [close] evicts its idle connections, so a shared instance stays usable afterwards. Each control connection derives
 * a pool of its own and evicts it itself ([ArcTunnelControl]).
 */
class ArcTunnelNet(
    trustedCaPem: String?,
    private val edgeOverride: ArcTunnelEdgeOverride?,
) {
    private val roots: KeyStore? = trustedCaPem?.takeIf { it.isNotBlank() }?.let { loadRoots(it) }
    private val trust: X509TrustManager = trustManager(roots)
    private val dns: Dns =
        if (edgeOverride == null) Dns.SYSTEM else Dns { listOf(InetAddress.getByName(edgeOverride.host)) }
    private val base: OkHttpClient = OkHttpClient.Builder().dns(dns).build()

    fun url(
        host: String,
        path: String,
    ): String = if (edgeOverride == null) "https://$host$path" else "https://$host:${edgeOverride.port}$path"

    fun serverClient(): OkHttpClient = client(sslContext(null))

    fun mtlsClient(identity: ArcTunnelIdentity): OkHttpClient = client(mtlsContext(identity))

    /**
     * The control-stream client: no read timeout, so the 90 s watchdog is what bounds a TLS handshake through it. On an
     * open duplex stream OkHttp applies no read timeout anyway.
     */
    fun controlClient(identity: ArcTunnelIdentity): OkHttpClient =
        mtlsClient(identity).newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Dial-backs to loopback/link-local targets are allowed only with the debug edge override. */
    fun dialBack(
        controlHost: String,
        identity: ArcTunnelIdentity,
    ): ArcTunnelDialBack = ArcTunnelDialBack(controlHost, mtlsContext(identity), sslContext(null), edgeOverride != null)

    /** Evicts the idle connections of the shared pool; the instance stays usable. */
    fun close() {
        base.connectionPool.evictAll()
    }

    /**
     * Whether [chain] reaches a root this phone trusts with its own certificates alone, which is what a client that does
     * not fetch intermediates needs. How many certificates that takes depends on the CA and on the phone's roots.
     *
     * Every check has its own trust manager, over an explicit key store: a trust manager remembers the intermediates it
     * verified, and without a key store Android hands out one per process, so a bare leaf would pass after any connection
     * through the same CA.
     */
    fun reachesTrustedRoot(chain: List<X509Certificate>): Boolean {
        if (chain.isEmpty()) return false
        return try {
            trustManager(roots ?: androidRoots()).checkServerTrusted(chain.toTypedArray(), ANY_KEY_EXCHANGE)
            true
        } catch (e: CertificateException) {
            Log.i(TAG, "the chain does not reach a trusted root", e)
            false
        }
    }

    private fun androidRoots(): KeyStore = KeyStore.getInstance(ANDROID_ROOTS_STORE).apply { load(null, null) }

    private fun mtlsContext(identity: ArcTunnelIdentity): SSLContext = sslContext(DynamicIdentityKeyManager(identity))

    private fun sslContext(km: X509ExtendedKeyManager?): SSLContext =
        SSLContext.getInstance("TLS").apply { init(km?.let { arrayOf(it) }, arrayOf(trust), SecureRandom()) }

    private fun client(ctx: SSLContext): OkHttpClient =
        base
            .newBuilder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .build()

    private fun loadRoots(pem: String): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ArcTunnelPem.parseChain(pem).forEachIndexed { i, c -> ks.setCertificateEntry("root$i", c) }
        return ks
    }

    private fun trustManager(roots: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(roots)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Resolves the CURRENT identity cert+key atomically per handshake, so a renewal mid-handshake stays consistent. */
    class DynamicIdentityKeyManager(
        private val identity: ArcTunnelIdentity,
    ) : X509ExtendedKeyManager() {
        private val selected = ThreadLocal<ArcTunnelCred>()

        private fun pick(): String = identity.identityCred().also { selected.set(it) }.alias

        private fun cred(): ArcTunnelCred = selected.get() ?: identity.identityCred().also { selected.set(it) }

        override fun getClientAliases(
            k: String?,
            i: Array<Principal>?,
        ): Array<String> = arrayOf(pick())

        override fun chooseClientAlias(
            k: Array<out String>?,
            i: Array<Principal>?,
            s: Socket?,
        ): String = pick()

        override fun chooseEngineClientAlias(
            k: Array<out String>?,
            i: Array<Principal>?,
            e: SSLEngine?,
        ): String = pick()

        override fun getServerAliases(
            k: String?,
            i: Array<Principal>?,
        ): Array<String>? = null

        override fun chooseServerAlias(
            k: String?,
            i: Array<Principal>?,
            s: Socket?,
        ): String? = null

        override fun chooseEngineServerAlias(
            k: String?,
            i: Array<Principal>?,
            e: SSLEngine?,
        ): String? = null

        override fun getCertificateChain(a: String?): Array<X509Certificate> = cred().chain.toTypedArray()

        override fun getPrivateKey(a: String?) = cred().key
    }

    private companion object {
        const val TAG = "MCP:ArcTunnelNet"
        const val ANDROID_ROOTS_STORE = "AndroidCAStore"

        /** The auth type accepted for any key: only the chain matters here. */
        const val ANY_KEY_EXCHANGE = "UNKNOWN"
    }
}
