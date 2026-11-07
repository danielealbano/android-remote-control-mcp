package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException

internal const val BYTES_PER_KIB = 1024

internal class ShellProcessTable {
    private val processes = LinkedHashMap<Int, ShellProcess>()
    private var nextId = 1

    @Synchronized
    fun register(
        launch: ShellLaunch,
        backend: ShellBackend,
        config: ShellConfig,
        nowMs: Long,
        onRegistered: (ShellProcess) -> Unit,
    ): ShellProcess {
        val maxRunning = config.limit(ShellLimit.MAX_RUNNING_PROCESSES)
        if (processes.values.count { it.isRunning } >= maxRunning) {
            throw McpToolException.ActionFailed(
                "$maxRunning shell processes are already running (the limit); wait for one to end or stop one " +
                    "with the shell kill tool",
            )
        }
        val process =
            ShellProcess(
                id = nextId++,
                launch = launch,
                backend = backend,
                buffer = ShellOutputBuffer(config.limit(ShellLimit.OUTPUT_BUFFER_KIB) * BYTES_PER_KIB),
                startedAtMs = nowMs,
            )
        processes[process.id] = process
        onRegistered(process)
        return process
    }

    @Synchronized
    fun get(id: Int): ShellProcess =
        processes[id]
            ?: throw McpToolException.InvalidParams(
                "Unknown process_id $id: it never existed, the server restarted, or it was dropped from the " +
                    "finished list",
            )

    @Synchronized
    fun all(): List<ShellProcess> = processes.values.toList()

    @Synchronized
    fun trimFinished(maxFinished: Int) {
        val finished = processes.values.filter { !it.isRunning }.sortedBy { it.snapshot().endedAtMs ?: 0L }
        finished.take(maxOf(0, finished.size - maxFinished)).forEach { processes.remove(it.id) }
    }

    @Synchronized
    fun clear(): List<ShellProcess> = processes.values.toList().also { processes.clear() }
}
