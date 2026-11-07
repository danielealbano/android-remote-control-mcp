package com.danielealbano.androidremotecontrolmcp.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/** The PUBLIC side of a tunnel: what any Internet client does, minus the DNS the e2e replaces. */
object ArcTunnelPublicClient {
    private const val READ_LIMIT = 64 * 1024

    /**
     * `GET [path]` through the edge over one raw TLS connection that offers no ALPN; returns the HTTP status
     * line + body.
     */
    fun get(
        stack: ArcTunnelStack,
        fqdn: String,
        path: String,
        edgePort: Int = ArcTunnelStack.EDGE_HOST_PORT,
    ): String {
        val socket: SSLSocket = stack.connectTls(fqdn, edgePort)
        socket.use {
            it.outputStream.write("GET $path HTTP/1.1\r\nHost: $fqdn\r\nConnection: close\r\n\r\n".toByteArray())
            it.outputStream.flush()
            return it.inputStream.readNBytes(READ_LIMIT).decodeToString()
        }
    }

    /** The certificates a client receives from the phone; a chain that does not reach the root fails the handshake. */
    fun servedChain(
        stack: ArcTunnelStack,
        fqdn: String,
    ): List<X509Certificate> {
        stack.connectTls(fqdn).use { socket ->
            return socket.session.peerCertificates.map { it as X509Certificate }
        }
    }

    /**
     * One public connection that stays open between requests, through the edge on [edgePort]: it stays spliced to
     * the phone through the control connection that was serving when it opened.
     */
    class KeepAlive(
        stack: ArcTunnelStack,
        private val fqdn: String,
        edgePort: Int,
    ) : AutoCloseable {
        private val socket: SSLSocket = stack.connectTls(fqdn, edgePort)
        private val input = socket.inputStream.buffered()

        /** `GET [path]`: the response's status line. */
        fun get(path: String): String {
            socket.outputStream.write("GET $path HTTP/1.1\r\nHost: $fqdn\r\n\r\n".toByteArray())
            socket.outputStream.flush()
            val status = readLine()
            var contentLength = 0
            var header = readLine()
            while (header.isNotEmpty()) {
                val (name, value) = header.split(':', limit = 2)
                if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.trim().toInt()
                header = readLine()
            }
            input.readNBytes(contentLength)
            return status
        }

        override fun close() {
            socket.close()
        }

        private fun readLine(): String =
            generateSequence { input.read() }
                .takeWhile { it >= 0 && it != '\n'.code }
                .map { it.toChar() }
                .joinToString("")
                .removeSuffix("\r")
    }

    /** The public URL of [fqdn] on the local relay port. */
    fun stackUrl(fqdn: String): String = "https://$fqdn:${ArcTunnelStack.EDGE_HOST_PORT}"

    /**
     * An OkHttp client whose HTTPS goes to the local relay with the real name as SNI/Host, trusting the Pebble
     * root; it offers HTTP/2 and HTTP/1.1 over ALPN.
     */
    fun stackOkHttp(stack: ArcTunnelStack): OkHttpClient {
        val trust = stack.pebbleTrustManager()
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        return OkHttpClient
            .Builder()
            .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
            .sslSocketFactory(ctx.socketFactory, trust)
            .build()
    }

    /** An MCP client over [stackOkHttp]. */
    fun stackMcpClient(
        stack: ArcTunnelStack,
        fqdn: String,
        bearerToken: String,
    ): McpClient {
        val okHttp = stackOkHttp(stack)
        val http: HttpClient = McpClient.httpClient(OkHttp) { preconfigured = okHttp }
        return McpClient(stackUrl(fqdn), bearerToken, http)
    }

    /** An MCP client with the JVM's default trust store (production: a WebPKI certificate). */
    fun systemTrustMcpClient(
        publicUrl: String,
        bearerToken: String,
    ): McpClient = McpClient(publicUrl, bearerToken, McpClient.httpClient(OkHttp) {})
}
