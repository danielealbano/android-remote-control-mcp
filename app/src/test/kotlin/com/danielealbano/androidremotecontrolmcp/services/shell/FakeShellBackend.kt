package com.danielealbano.androidremotecontrolmcp.services.shell

import kotlinx.coroutines.CompletableDeferred
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val PIPE_BYTES = 65_536

class FakeShellBackend(
    override val kind: ShellBackendKind = ShellBackendKind.SHIZUKU,
) : ShellBackend {
    class Spawned(
        val processId: Int,
        val script: String,
        val output: PipedOutputStream,
        val exit: CompletableDeferred<ShellExit>,
    ) {
        val kills = CopyOnWriteArrayList<Int?>()

        fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        fun writeHeader(
            pgid: Int,
            uid: Int,
        ) = write("$pgid $uid\n".toByteArray(Charsets.US_ASCII))

        fun end(
            code: Int?,
            reason: String? = null,
        ) {
            output.close()
            exit.complete(ShellExit(code, reason))
        }
    }

    val spawned = ConcurrentHashMap<Int, Spawned>()
    val released = CopyOnWriteArrayList<Int>()
    val releaseAllCalls = AtomicInteger()

    @Volatile
    var failure: Throwable? = null

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun spawn(
        processId: Int,
        script: String,
    ): SpawnedShell {
        gate?.await()
        failure?.let { throw it }
        val output = PipedOutputStream()
        val input = PipedInputStream(output, PIPE_BYTES)
        val record = Spawned(processId, script, output, CompletableDeferred())
        spawned[processId] = record
        return SpawnedShell(input, record.exit) { pgid -> record.kills += pgid }
    }

    override suspend fun release(processId: Int) {
        released += processId
    }

    override fun releaseAll() {
        releaseAllCalls.incrementAndGet()
    }
}
