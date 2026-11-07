package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

private const val TAG = "MCP:ShellProcessWatcher"
private const val READ_CHUNK_BYTES = 8192
private const val HEADER_WAIT_MS = 2_000L
private const val OUTPUT_DRAIN_MS = 2_000L

internal class ShellProcessWatcher(
    private val scope: CoroutineScope,
    private val table: ShellProcessTable,
    private val audit: ShellAudit,
    private val currentConfig: suspend () -> ShellConfig,
    private val clock: () -> Long,
) {
    fun watch(
        process: ShellProcess,
        spawned: SpawnedShell,
    ) {
        thread(isDaemon = true, name = "shell-pump-${process.id}") { pump(process, spawned.output) }
        scope.launch {
            val exit = spawned.exit.await()
            withTimeoutOrNull(HEADER_WAIT_MS) { process.headerRead.await() }
            process.killLeftovers()
            withTimeoutOrNull(OUTPUT_DRAIN_MS) { process.pumpDone.await() }
            end(process, exit)
            process.backend.release(process.id)
            table.trimFinished(currentConfig().limit(ShellLimit.MAX_FINISHED_PROCESSES))
        }
    }

    fun end(
        process: ShellProcess,
        exit: ShellExit,
    ) {
        process.timeoutJob?.cancel()
        if (!process.finish(exit, clock())) return
        process.closeOutput()
        audit.ended(process)
    }

    private fun pump(
        process: ShellProcess,
        input: InputStream,
    ) {
        try {
            input.use {
                val read = ShellScriptBuilder.readHeader(it)
                process.headerArrived(read.header)
                process.append(read.leftover, read.leftover.size)
                val chunk = ByteArray(READ_CHUNK_BYTES)
                var count = it.read(chunk)
                while (count >= 0) {
                    process.append(chunk, count)
                    count = it.read(chunk)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Output of process ${process.id} closed", e)
        } finally {
            process.headerArrived(null)
            process.pumpDone.complete(Unit)
        }
    }
}
