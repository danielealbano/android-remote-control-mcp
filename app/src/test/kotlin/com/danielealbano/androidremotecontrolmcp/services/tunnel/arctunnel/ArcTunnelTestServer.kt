package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketHandler
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.Socket
import okio.buffer
import java.io.IOException
import java.net.InetAddress

object ArcTunnelTestServer {
    const val BASE = "example.test"
    const val ENROLL_HOST = "enroll.example.test"
    const val CONTROL_HOST = "connect.example.test"
    val hosts: ArcTunnelHosts = ArcTunnelHosts(BASE)
    val held: HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(CONTROL_HOST)
            .addSubjectAlternativeName(CONTROL_HOST)
            .addSubjectAlternativeName(ENROLL_HOST)
            .addSubjectAlternativeName("*.d.$BASE")
            .build()
    val certPem: String = ArcTunnelPem.encode("CERTIFICATE", held.certificate.encoded)
    private val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

    fun start(): MockWebServer =
        MockWebServer().also {
            it.useHttps(serverCerts.sslSocketFactory())
            it.start(InetAddress.getLoopbackAddress(), 0)
        }

    fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            .build()

    /** A net trusting the test certificate whose hosts resolve to the mock server on [port]. */
    fun net(port: Int): ArcTunnelNet = ArcTunnelNet(certPem, ArcTunnelEdgeOverride("127.0.0.1", port))

    fun answer(
        code: Int,
        onStream: (Socket) -> Unit,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .removeHeader("Content-Length")
            .socketHandler(
                object : SocketHandler {
                    override fun handle(socket: Socket) = onStream(socket)
                },
            ).build()

    /** A bound control stream that sends nothing and stays open until the client ends it. */
    fun hold(): MockResponse =
        answer(200) { s ->
            try {
                s.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("held control stream ended: $e")
            }
        }

    fun json(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .body(body)
            .build()

    fun frame(
        type: Int,
        payload: String = "",
    ): ByteArray {
        val p = payload.toByteArray()
        return Buffer()
            .writeByte(type)
            .writeInt(p.size)
            .write(p)
            .readByteArray()
    }
}
