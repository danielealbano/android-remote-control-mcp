package com.danielealbano.androidremotecontrolmcp.services.storage

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

@DisplayName("SslUtils")
class SslUtilsTest {
    @Test
    fun `configurePermissiveSsl sets SSLSocketFactory on connection`() {
        val connection = mockk<HttpsURLConnection>(relaxed = true)

        SslUtils.configurePermissiveSsl(connection)

        verify { connection.sslSocketFactory = any<SSLSocketFactory>() }
    }

    @Test
    fun `configurePermissiveSsl sets permissive HostnameVerifier`() {
        val connection = mockk<HttpsURLConnection>(relaxed = true)

        SslUtils.configurePermissiveSsl(connection)

        verify { connection.hostnameVerifier = any() }
    }
}
