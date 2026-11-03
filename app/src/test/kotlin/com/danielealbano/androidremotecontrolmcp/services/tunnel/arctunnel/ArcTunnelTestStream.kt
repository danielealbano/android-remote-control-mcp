package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import mockwebserver3.MockResponse
import okio.Socket
import okio.buffer
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import java.net.Socket as JavaSocket

/**
 * A control stream the test drives: frames in [outgoing] are written and PONGs counted in [pongs]; [end] ends it
 * from the server side, [clientEnded] tells that the client ended its side.
 */
class ArcTunnelTestStream(
    vararg first: ByteArray,
) {
    val outgoing = LinkedBlockingQueue(first.toList())
    val pongs = Semaphore(0)
    val end = CountDownLatch(1)
    val clientEnded = CountDownLatch(1)

    fun response(): MockResponse =
        ArcTunnelTestServer.answer(200) { socket: Socket ->
            val reader =
                Thread {
                    try {
                        val source = socket.source.buffer()
                        var frame = ArcTunnelFrames.read(source)
                        while (frame != null) {
                            if (frame.first == ArcTunnelFrames.PONG) pongs.release()
                            frame = ArcTunnelFrames.read(source)
                        }
                    } catch (e: IOException) {
                        System.err.println("driven stream read ended: $e")
                    } finally {
                        clientEnded.countDown()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                val sink = socket.sink.buffer()
                while (end.count > 0 && clientEnded.count > 0) {
                    val frame = outgoing.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    sink.write(frame)
                    sink.flush()
                }
                if (end.count == 0L) sink.close()
            } catch (e: IOException) {
                System.err.println("driven stream write ended: $e")
            }
            reader.join(JOIN_MS)
        }

    fun assertAnswersPing() {
        outgoing += ArcTunnelTestServer.frame(ArcTunnelFrames.PING)
        assertTrue(pongs.tryAcquire(BUDGET_S, TimeUnit.SECONDS), "the connection must still answer PING")
    }

    private companion object {
        const val POLL_MS = 10L
        const val JOIN_MS = 1_000L
        const val BUDGET_S = 10L
    }
}

/** Records the raw sockets of a client, in the order the connections were opened. */
class ArcTunnelTestSocketFactory : SocketFactory() {
    val sockets = CopyOnWriteArrayList<JavaSocket>()

    override fun createSocket(): JavaSocket = JavaSocket().also { sockets += it }

    override fun createSocket(
        host: String,
        port: Int,
    ): JavaSocket = throw UnsupportedOperationException()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): JavaSocket = throw UnsupportedOperationException()

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): JavaSocket = throw UnsupportedOperationException()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): JavaSocket = throw UnsupportedOperationException()
}
