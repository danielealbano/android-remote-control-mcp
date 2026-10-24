package com.danielealbano.androidremotecontrolmcp.mcp

import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("cancelledOnClientDisconnect")
class McpStatelessTransportTest {
    @Test
    fun `the channel closing while the request runs cancels it`() =
        runBlocking {
            val channel = EmbeddedChannel()
            val started = CompletableDeferred<Unit>()
            val request =
                launch {
                    cancelledOnClientDisconnect(channel) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()

            channel.close()

            withTimeout(WAIT_MS) { request.join() }
            assertTrue(request.isCancelled)
        }

    @Test
    fun `a channel already closed cancels the request at once`() =
        runBlocking {
            val channel = EmbeddedChannel().also { it.close() }

            val request = launch { cancelledOnClientDisconnect(channel) { awaitCancellation() } }

            withTimeout(WAIT_MS) { request.join() }
            assertTrue(request.isCancelled)
        }

    @Test
    fun `the channel closing after the request ended cancels nothing`() =
        runBlocking {
            val channel = EmbeddedChannel()
            cancelledOnClientDisconnect(channel) {}

            // A listener left on the channel would cancel this coroutine, the one that served the request
            channel.close()

            assertTrue(currentCoroutineContext().isActive)
        }

    @Test
    fun `without a Netty channel the request just runs`() =
        runBlocking {
            var ran = false

            cancelledOnClientDisconnect(null) { ran = true }

            assertTrue(ran)
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
