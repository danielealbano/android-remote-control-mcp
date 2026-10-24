package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@DisplayName("ArcTunnelDialBack")
class ArcTunnelDialBackTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val peer = ServerSocket(0, BACKLOG, loopback)
    private val clientTls: SSLContext =
        HandshakeCertificates
            .Builder()
            .addTrustedCertificate(ArcTunnelTestServer.held.certificate)
            .build()
            .sslContext()
    private val sessions = ArcTunnelSessions {}
    private val fillers = mutableListOf<Socket>()

    /** The sockets the dial-back applied its keepalive to, and whether each was connected at that moment. */
    private val keptAlive = mutableListOf<Pair<Socket, Boolean>>()

    @AfterEach
    fun tearDown() {
        fillers.forEach { it.close() }
        peer.close()
    }

    private fun dialBack(
        timeoutMs: Long = OPEN_TIMEOUT_MS,
        allowLocal: Boolean = true,
    ) = ArcTunnelDialBack(ArcTunnelTestServer.CONTROL_HOST, clientTls, clientTls, allowLocal, timeoutMs) { socket ->
        keptAlive += socket to socket.isConnected
    }

    private fun target(
        addr: String = "127.0.0.1:${peer.localPort}",
        https: Boolean = false,
    ) = ArcTunnelDialBack.Target("t", addr, https, false)

    /** Runs [answer] on the next accepted peer socket after draining the upgrade request (into [request]). */
    private fun servePeer(
        request: AtomicReference<String> = AtomicReference(),
        answer: (Socket) -> Unit,
    ): Thread =
        Thread {
            try {
                peer.accept().use { socket ->
                    request.set(readHeaders(socket.getInputStream()))
                    answer(socket)
                }
            } catch (e: IOException) {
                System.err.println("dial-back peer ended: $e")
            }
        }.apply {
            isDaemon = true
            start()
        }

    private fun readHeaders(input: InputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            text.append(b.toChar())
        }
        return text.toString()
    }

    private fun respond(
        socket: Socket,
        statusLine: String,
    ) {
        socket.getOutputStream().apply {
            write("$statusLine\r\n\r\n".toByteArray())
            flush()
        }
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test
    fun `101 returns the connection and sends the upgrade request`() =
        runBlocking {
            val request = AtomicReference<String>()
            val done = CountDownLatch(1)
            servePeer(request) {
                respond(it, "HTTP/1.1 101 Switching Protocols")
                done.await(5, TimeUnit.SECONDS)
            }

            val conn = dialBack().open(target(), sessions)

            assertTrue(request.get().contains("X-Dial-Token: t\r\n"), request.get())
            assertTrue(request.get().contains("Upgrade: tunneld-dial\r\n"), request.get())
            assertTrue(request.get().startsWith("GET /api/v1/data HTTP/1.1\r\n"), request.get())
            conn.socket.close()
            done.countDown()
        }

    @Test
    fun `the keepalive is applied to the connected raw socket`() =
        runBlocking {
            val done = CountDownLatch(1)
            servePeer {
                respond(it, "HTTP/1.1 101 Switching Protocols")
                done.await(5, TimeUnit.SECONDS)
            }

            val conn = dialBack().open(target(), sessions)

            assertEquals(listOf(conn.raw to true), keptAlive)
            conn.socket.close()
            done.countDown()
        }

    @Test
    fun `non-101 throws IOException`() =
        runBlocking {
            servePeer { respond(it, "HTTP/1.1 404 Not Found") }

            val error = failureOf { dialBack().open(target(), sessions) }

            assertTrue(error is IOException, "error $error")
            assertEquals(0, sessions.size())
        }

    @Test
    fun `bad address throws IllegalArgumentException`() =
        runBlocking {
            listOf("nohost", "h:x").forEach { addr ->
                val error = failureOf { dialBack().open(target(addr), sessions) }
                assertTrue(error is IllegalArgumentException, "$addr: $error")
            }
            assertEquals(0, sessions.size())
        }

    @Test
    fun `local targets are refused without the override flag`() =
        runBlocking {
            listOf("127.0.0.1:${peer.localPort}", "169.254.1.1:443", "0.0.0.0:443").forEach { addr ->
                val error = failureOf { dialBack(allowLocal = false).open(target(addr), sessions) }
                assertTrue(error is IllegalArgumentException, "$addr: $error")
            }
            peer.soTimeout = NOTHING_ACCEPTED_MS
            assertTrue(failureOf { peer.accept().close() } is SocketTimeoutException)
            assertEquals(0, sessions.size())
        }

    @Test
    fun `deadline ends a stalled connect`() =
        runBlocking {
            val full = ServerSocket(0, 1, loopback)
            try {
                // Fill the accept queue so further SYNs are dropped and a connect stalls.
                repeat(FILLERS) {
                    runCatching {
                        Socket().also { s ->
                            fillers += s
                            s.connect(InetSocketAddress(loopback, full.localPort), FILLER_CONNECT_MS)
                        }
                    }
                }
                val startedAt = System.nanoTime()

                val stalled = target("127.0.0.1:${full.localPort}")
                val error = failureOf { dialBack(timeoutMs = DEADLINE_MS).open(stalled, sessions) }

                assertTrue(error is SocketTimeoutException, "error $error")
                assertTrue((System.nanoTime() - startedAt) / NANOS_PER_MS < BUDGET_MS)
                assertEquals(0, sessions.size())
            } finally {
                full.close()
            }
        }

    @Test
    fun `deadline ends trickled headers`() =
        runBlocking {
            servePeer { socket ->
                val out = socket.getOutputStream()
                "HTTP/1.1 101 Switching Protocols\r\n\r\n".forEach {
                    out.write(it.code)
                    out.flush()
                    Thread.sleep(TRICKLE_MS)
                }
            }

            val error = failureOf { dialBack(timeoutMs = DEADLINE_MS).open(target(), sessions) }

            assertTrue(error is SocketTimeoutException, "error $error")
            assertEquals(0, sessions.size())
        }

    @Test
    fun `peer close during the upgrade throws IOException`() =
        runBlocking {
            servePeer { it.close() }

            val error = failureOf { dialBack().open(target(), sessions) }

            assertTrue(error is IOException, "error $error")
            assertEquals(0, sessions.size())
        }

    @Test
    fun `a failed open leaves no session registered`() =
        runBlocking {
            // One shared registry across the failure kinds: refused upgrade, bad address, local target, peer close.
            servePeer { respond(it, "HTTP/1.1 404 Not Found") }
            assertTrue(failureOf { dialBack().open(target(), sessions) } is IOException)
            assertEquals(0, sessions.size())

            assertTrue(failureOf { dialBack().open(target("nohost"), sessions) } is IllegalArgumentException)
            assertEquals(0, sessions.size())

            val local = target("127.0.0.1:${peer.localPort}")
            assertTrue(failureOf { dialBack(allowLocal = false).open(local, sessions) } is IllegalArgumentException)
            assertEquals(0, sessions.size())

            servePeer { it.close() }
            assertTrue(failureOf { dialBack().open(target(), sessions) } is IOException)
            assertEquals(0, sessions.size())
        }

    @Test
    fun `a successful open keeps the raw socket registered`() =
        runBlocking {
            val done = CountDownLatch(1)
            servePeer {
                respond(it, "HTTP/1.1 101 Switching Protocols")
                done.await(5, TimeUnit.SECONDS)
            }

            val conn = dialBack().open(target(), sessions)

            assertEquals(1, sessions.size())
            sessions.release(conn.raw)
            assertEquals(0, sessions.size())
            conn.socket.close()
            done.countDown()
        }

    @Test
    fun `raw socket is tracked by the sessions`() =
        runBlocking {
            val done = CountDownLatch(1)
            servePeer { done.await(5, TimeUnit.SECONDS) }
            val pending = async(Dispatchers.IO) { failureOf { dialBack(timeoutMs = 5_000).open(target(), sessions) } }
            while (sessions.size() == 0) delay(POLL_MS)

            sessions.closeAll()

            assertTrue(pending.await() is IOException)
            done.countDown()
        }

    @Test
    fun `shut sessions refuse a new dial-back`() =
        runBlocking {
            sessions.closeAll()

            val error = failureOf { dialBack().open(target(), sessions) }

            assertTrue(error is IOException, "error $error")
            peer.soTimeout = NOTHING_ACCEPTED_MS
            assertTrue(failureOf { peer.accept().close() } is SocketTimeoutException)
        }

    @Test
    fun `TLS dial-back verifies the host name and ALPN`() =
        runBlocking {
            val alpn = AtomicReference<String>()
            tlsPeer(ArcTunnelTestServer.held, alpn).use { server ->
                val conn = dialBack().open(target("127.0.0.1:${server.localPort}", https = true), sessions)
                conn.socket.close()
            }
            assertEquals("http/1.1", alpn.get())

            val other = HeldCertificate.Builder().commonName("other.example.test").build()
            tlsPeer(other, AtomicReference()).use { server ->
                val tlsTarget = target("127.0.0.1:${server.localPort}", https = true)
                val error = failureOf { dialBack().open(tlsTarget, sessions) }
                assertTrue(error is IOException, "error $error")
            }
        }

    /** A TLS dial-back endpoint serving [held] that records the negotiated ALPN protocol and answers 101. */
    private fun tlsPeer(
        held: HeldCertificate,
        alpn: AtomicReference<String>,
    ): SSLServerSocket {
        val context =
            HandshakeCertificates
                .Builder()
                .heldCertificate(held)
                .build()
                .sslContext()
        val server = context.serverSocketFactory.createServerSocket(0, BACKLOG, loopback) as SSLServerSocket
        server.sslParameters = server.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
        Thread {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    alpn.set(socket.applicationProtocol)
                    readHeaders(socket.getInputStream())
                    respond(socket, "HTTP/1.1 101 Switching Protocols")
                }
            } catch (e: IOException) {
                System.err.println("TLS dial-back peer ended: $e")
            }
        }.apply {
            isDaemon = true
            start()
        }
        return server
    }

    private companion object {
        const val BACKLOG = 50
        const val DEADLINE_MS = 500L
        const val OPEN_TIMEOUT_MS = 10_000L
        const val BUDGET_MS = 2_000L
        const val NANOS_PER_MS = 1_000_000L
        const val TRICKLE_MS = 100L
        const val POLL_MS = 20L
        const val FILLERS = 8
        const val FILLER_CONNECT_MS = 200
        const val NOTHING_ACCEPTED_MS = 300
    }
}
