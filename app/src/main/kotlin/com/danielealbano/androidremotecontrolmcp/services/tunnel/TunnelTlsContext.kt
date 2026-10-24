package com.danielealbano.androidremotecontrolmcp.services.tunnel

import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Server TLS of the tunnel listener, built from a key manager that hands the key handle and the public chain
 * straight to the TLS stack. The key never goes into a `KeyStore`: an AndroidKeyStore key cannot be exported
 * (its format is null), which the default `KeyStore` type rejects on Android 13 and 14; the TLS stack signs
 * through AndroidKeyStore instead. The JDK provider is used because OpenSSL would need the key material; the
 * ciphers are the HTTP/2-safe ones Ktor's `sslConnector` uses.
 */
object TunnelTlsContext {
    /**
     * Pipeline name of the TLS handler (a repeated pipeline hook never adds a second one). It MUST be `ssl`:
     * Ktor reports an HTTP/1.1 request as `https` only when the pipeline holds a handler of that name, and the
     * OAuth metadata, the token audience and the share links are built from that scheme.
     */
    const val HANDLER_NAME = "ssl"
    private const val ALIAS = "tunnel"
    private val PROTOCOLS = arrayOf(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1)

    fun create(material: TunnelTlsMaterial): SslContext =
        SslContextBuilder
            .forServer(KeyManager(material))
            .sslProvider(SslProvider.JDK)
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .build()

    /**
     * A TLS handler offering `h2` and `http/1.1` through the engine's own ALPN parameters, not Netty's ALPN
     * negotiator: on Android the negotiator fails a client that offers no ALPN (the engine reports no protocol as
     * null), while the HTTP handler behind TLS detects the protocol from the bytes anyway.
     */
    fun newHandler(
        context: SslContext,
        allocator: ByteBufAllocator,
    ): SslHandler {
        val engine = context.newEngine(allocator)
        engine.sslParameters = engine.sslParameters.apply { applicationProtocols = PROTOCOLS }
        return SslHandler(engine)
    }

    /** Offers the one key for the handshake key types it can serve (`EC`, or `EC_EC` style on TLS 1.2). */
    private class KeyManager(
        material: TunnelTlsMaterial,
    ) : X509ExtendedKeyManager() {
        private val key: PrivateKey = material.privateKey
        private val chain: Array<X509Certificate> = material.chain.toTypedArray()

        private fun alias(keyType: String?): String? {
            val usable = keyType == null || keyType.startsWith(key.algorithm)
            return if (usable) ALIAS else null
        }

        override fun chooseEngineServerAlias(
            k: String?,
            i: Array<out Principal>?,
            e: SSLEngine?,
        ): String? = alias(k)

        override fun chooseServerAlias(
            k: String?,
            i: Array<out Principal>?,
            s: Socket?,
        ): String? = alias(k)

        override fun getServerAliases(
            k: String?,
            i: Array<out Principal>?,
        ): Array<String>? = alias(k)?.let { arrayOf(it) }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            val ours = alias == ALIAS
            return if (ours) chain.clone() else null
        }

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null

        override fun getClientAliases(
            k: String?,
            i: Array<out Principal>?,
        ): Array<String>? = null

        override fun chooseClientAlias(
            k: Array<out String>?,
            i: Array<out Principal>?,
            s: Socket?,
        ): String? = null
    }
}
