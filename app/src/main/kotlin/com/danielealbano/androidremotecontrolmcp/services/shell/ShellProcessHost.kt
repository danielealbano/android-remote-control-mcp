package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "MCP:ShellProcessHost"
private const val HEADER_WAIT_MS = 2_000L

class ShellProcessHost(
    private val shellPath: String,
    private val killGroup: (Int) -> Unit,
) {
    private class Hosted(
        val process: Process,
        private val killGroup: (Int) -> Unit,
    ) {
        val headerRead = CountDownLatch(1)
        private var headerSeen = false
        private var stopRequested = false
        private var pgid: Int? = null

        @Synchronized
        fun kill() {
            if (headerSeen) {
                killNow()
            } else {
                stopRequested = true
            }
        }

        @Synchronized
        fun killBeforeExit() {
            if (headerSeen) killNow() else process.destroyForcibly()
        }

        @Synchronized
        fun headerArrived(group: Int?) {
            if (headerSeen) return
            headerSeen = true
            pgid = group
            headerRead.countDown()
            if (stopRequested) killNow()
        }

        private fun killNow() {
            val group = pgid
            if (group != null) killGroup(group) else process.destroyForcibly()
        }
    }

    private val processes = ConcurrentHashMap<Int, Hosted>()

    fun start(
        processId: Int,
        script: String,
        output: OutputStream,
        onExit: (Int) -> Boolean,
    ) {
        val process = ProcessBuilder(shellPath, "-c", script).redirectErrorStream(true).start()
        process.outputStream.close()
        val hosted = Hosted(process, killGroup)
        processes[processId] = hosted
        thread(isDaemon = true, name = "shell-host-output") { forward(hosted, output) }
        thread(isDaemon = true, name = "shell-host-wait") {
            val code = process.waitFor()
            hosted.headerRead.await(HEADER_WAIT_MS, TimeUnit.MILLISECONDS)
            hosted.kill()
            processes.remove(processId)
            if (!onExit(code)) killAll()
        }
    }

    fun kill(processId: Int) {
        processes[processId]?.kill()
    }

    fun killAll() {
        processes.values.forEach { it.killBeforeExit() }
    }

    private fun forward(
        hosted: Hosted,
        output: OutputStream,
    ) {
        try {
            output.use { out ->
                val read = ShellScriptBuilder.readHeader(hosted.process.inputStream)
                hosted.headerArrived(read.header?.pgid)
                read.header?.let { out.write(it.line()) }
                out.write(read.leftover)
                out.flush()
                hosted.process.inputStream.copyTo(out)
            }
        } catch (e: IOException) {
            Log.w(TAG, "The output reader is gone; stopping the process", e)
            hosted.kill()
        } finally {
            hosted.headerArrived(null)
        }
    }
}
