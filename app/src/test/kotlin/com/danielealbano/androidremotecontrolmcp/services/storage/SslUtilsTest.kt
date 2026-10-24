package com.danielealbano.androidremotecontrolmcp.services.storage

import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

@DisplayName("SslUtils")
class SslUtilsTest {
    private fun trustingClient(): OkHttpClient = OkHttpClient.Builder().also(SslUtils::trustAllCertificates).build()

    @Test
    fun `trustAllCertificates accepts any host name`() {
        val client = trustingClient()

        assertTrue(client.hostnameVerifier.verify("any.example", mockk<SSLSession>(relaxed = true)))
    }

    @Test
    fun `trustAllCertificates installs a trust manager accepting any chain`() {
        val trustManager = trustingClient().x509TrustManager

        assertNotNull(trustManager)
        trustManager!!.checkServerTrusted(arrayOf<X509Certificate>(mockk(relaxed = true)), "RSA")
        assertEquals(0, trustManager.acceptedIssuers.size)
    }
}
