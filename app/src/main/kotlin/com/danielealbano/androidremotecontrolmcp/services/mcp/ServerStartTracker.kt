package com.danielealbano.androidremotecontrolmcp.services.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The server start in progress of [McpServerService]. A teardown cancels it and waits for it first: a start
 * left running would bring the server and the tunnel up after the teardown, with no stop to come.
 */
internal class ServerStartTracker(
    private val scope: CoroutineScope,
    private val joinTimeoutMs: Long,
) {
    @Volatile
    private var job: Job? = null

    fun launch(start: suspend () -> Unit) {
        job = scope.launch { start() }
    }

    /**
     * Cancels the start in progress and blocks until it has ended (it stops at its next suspension point), at most
     * [joinTimeoutMs]; false when it is still running after that.
     */
    fun cancelAndAwait(): Boolean {
        val current = job ?: return true
        return runBlocking { withTimeoutOrNull(joinTimeoutMs) { current.cancelAndJoin() } } != null
    }
}
