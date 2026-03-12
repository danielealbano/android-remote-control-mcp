package com.danielealbano.androidremotecontrolmcp.services.storage

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared SSL utility for permissive HTTPS connections.
 * Used by both SAF and MediaStore download implementations when
 * the user has enabled `allowUnverifiedHttpsCerts`.
 *
 * The trust-all pattern inherently requires empty `checkClientTrusted`/`checkServerTrusted`
 * implementations — these suppressions are genuinely unavoidable.
 */
object SslUtils {
    fun configurePermissiveSsl(connection: HttpsURLConnection) {
        val trustAllManager =
            @Suppress("CustomX509TrustManager")
            object : X509TrustManager {
                @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { }

                @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        connection.sslSocketFactory = sslContext.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
