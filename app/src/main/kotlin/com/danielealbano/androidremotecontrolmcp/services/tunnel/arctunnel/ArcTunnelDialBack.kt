package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** [allowLocalTargets]: only the debug edge override may point a dial-back at a loopback/link-local address. */
class ArcTunnelDialBack(
    private val controlHost: String,
    private val mtls: SSLContext,
    private val serverTls: SSLContext,
    private val allowLocalTargets: Boolean = false,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /**
     * [socket] is what the splice reads/writes (the TLS socket when `use_https`); [raw] is the registered
     * TCP socket.
     */
    class Conn(
        val socket: Socket,
        val input: InputStream,
        val raw: Socket,
    )

    /** One OPEN's dial-back target. */
    data class Target(
        val token: String,
        val addr: String,
        val useHttps: Boolean,
        val useMtls: Boolean,
    )

    /**
     * Opens the dial-back; the raw socket is tracked by [sessions] for the stream's lifetime. Throws [IOException]
     * (refused, closed, timed out) or [IllegalArgumentException] (bad address).
     */
    suspend fun open(
        target: Target,
        sessions: ArcTunnelSessions,
    ): Conn =
        coroutineScope {
            val sep = target.addr.lastIndexOf(':')
            require(sep > 0) { "bad dial-back address ${target.addr}" }
            val hostPart = target.addr.substring(0, sep)
            val host = hostPart.removePrefix("[").removeSuffix("]")
            val port =
                requireNotNull(target.addr.substring(sep + 1).toIntOrNull()) { "bad dial-back port in ${target.addr}" }
            val raw = Socket()
            val state = AtomicInteger(OPENING)
            if (!sessions.add(raw)) throw IOException("control stream ended before the dial-back to ${target.addr}")
            val deadline =
                launch {
                    delay(timeoutMs)
                    if (state.compareAndSet(OPENING, TIMED_OUT)) ArcTunnelSockets.closeQuietly(raw)
                }
            val result = runCatching { withContext(Dispatchers.IO) { upgrade(raw, host, port, target) } }
            deadline.cancel()
            val conn = runCatching { finish(state, raw, target.addr, result) }
            conn.onFailure { sessions.release(raw) }
            conn.getOrThrow()
        }

    private fun finish(
        state: AtomicInteger,
        raw: Socket,
        addr: String,
        result: Result<Conn>,
    ): Conn {
        val opened = result.isSuccess && state.compareAndSet(OPENING, OPENED)
        if (opened) return result.getOrThrow()
        ArcTunnelSockets.closeQuietly(raw)
        val cause = result.exceptionOrNull()
        if (state.get() == TIMED_OUT) {
            throw SocketTimeoutException("dial-back to $addr took longer than ${timeoutMs}ms").apply {
                cause?.let { initCause(it) }
            }
        }
        throw cause ?: IOException("dial-back socket closed by its deadline")
    }

    private fun upgrade(
        raw: Socket,
        host: String,
        port: Int,
        target: Target,
    ): Conn {
        val address = InetAddress.getByName(host)
        require(allowLocalTargets || !isLocalTarget(address)) { "dial-back target $host is not allowed" }
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(address, port), timeoutMs.toInt())
        raw.soTimeout = timeoutMs.toInt()
        val socket = if (target.useHttps) tls(raw, port, if (target.useMtls) mtls else serverTls) else raw
        val request =
            "GET /api/v1/data HTTP/1.1\r\nHost: $controlHost\r\nConnection: Upgrade\r\n" +
                "Upgrade: tunneld-dial\r\nX-Dial-Token: ${target.token}\r\n\r\n"
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val input = BufferedInputStream(socket.getInputStream())
        val status = readStatus(input)
        if (status != SWITCHING_PROTOCOLS) throw IOException("dial-back refused: $status")
        socket.soTimeout = 0
        return Conn(socket, input, raw)
    }

    private fun tls(
        raw: Socket,
        port: Int,
        ctx: SSLContext,
    ): Socket {
        val socket = ctx.socketFactory.createSocket(raw, controlHost, port, true) as SSLSocket
        socket.sslParameters =
            socket.sslParameters.apply {
                serverNames = listOf(SNIHostName(controlHost))
                applicationProtocols = arrayOf("http/1.1")
                endpointIdentificationAlgorithm = "HTTPS"
            }
        socket.startHandshake()
        return socket
    }

    private fun readStatus(input: InputStream): Int {
        val statusLine = readLine(input)
        while (readLine(input).isNotEmpty()) {
            // drain the response headers
        }
        val parts = statusLine.split(' ')
        val status = if (parts.size >= 2 && parts[0].startsWith("HTTP/1.")) parts[1].toIntOrNull() else null
        return status ?: throw IOException("bad dial-back response: $statusLine")
    }

    /** A server must never make the phone connect to itself, the link or a multicast group. */
    private fun isLocalTarget(address: InetAddress): Boolean =
        listOf(
            address.isLoopbackAddress,
            address.isAnyLocalAddress,
            address.isLinkLocalAddress,
            address.isMulticastAddress,
        ).any { it }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        var b = input.read()
        while (b >= 0 && b != '\n'.code) {
            if (sb.length >= MAX_LINE) throw IOException("dial-back response line too long")
            sb.append(b.toChar())
            b = input.read()
        }
        if (b < 0) throw IOException("dial-back connection closed during the upgrade")
        return sb.toString().removeSuffix("\r")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_LINE = 8192
        const val SWITCHING_PROTOCOLS = 101
        const val OPENING = 0
        const val OPENED = 1
        const val TIMED_OUT = 2
    }
}
