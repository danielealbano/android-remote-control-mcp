package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.IOException

private const val TAG = "MCP:ShellProcess"

enum class ShellProcessState(
    val wireName: String,
) {
    RUNNING("running"),
    EXITED("exited"),
    KILLED("killed"),
    TIMED_OUT("timed_out"),
    FAILED("failed"),
}

enum class ElevationGrant(
    val wireName: String,
) {
    AUTO_ALLOWED("auto-allowed"),
    ALLOWLISTED("allowlisted"),
    APPROVED("approved by the user"),
}

data class ShellElevation(
    val justification: String,
    val grant: ElevationGrant,
)

data class ShellLaunch(
    val command: String,
    val workdir: String,
    val envs: Map<String, String>,
    val timeoutMinutes: Int,
    val elevation: ShellElevation? = null,
) {
    val elevated: Boolean
        get() = elevation != null
}

data class ShellProcessSnapshot(
    val id: Int,
    val command: String,
    val backend: ShellBackendKind,
    val elevated: Boolean,
    val uid: Int?,
    val state: ShellProcessState,
    val exitCode: Int?,
    val failure: String?,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val totalBytes: Long,
)

data class ShellProcessPage(
    val snapshot: ShellProcessSnapshot,
    val chunk: ShellOutputChunk,
)

internal data class ShellProgress(
    val totalBytes: Long,
    val state: ShellProcessState,
)

internal class ShellProcess(
    val id: Int,
    val launch: ShellLaunch,
    val backend: ShellBackend,
    val buffer: ShellOutputBuffer,
    private val startedAtMs: Long,
) {
    private val progress = MutableStateFlow(ShellProgress(0, ShellProcessState.RUNNING))
    val headerRead = CompletableDeferred<Unit>()
    val pumpDone = CompletableDeferred<Unit>()

    @Volatile
    var timeoutJob: Job? = null

    @Volatile
    private var spawned: SpawnedShell? = null

    @Volatile
    private var pgid: Int? = null

    @Volatile
    private var headerSeen = false

    private var uid: Int? = null
    private var stopReason: ShellProcessState? = null
    private var exitCode: Int? = null
    private var failure: String? = null
    private var endedAtMs: Long? = null

    val isRunning: Boolean
        get() = progress.value.state == ShellProcessState.RUNNING

    fun append(
        bytes: ByteArray,
        length: Int,
    ) {
        buffer.append(bytes, 0, length)
        progress.update { it.copy(totalBytes = buffer.totalBytes()) }
    }

    suspend fun awaitEnd() {
        progress.first { it.state != ShellProcessState.RUNNING }
    }

    suspend fun awaitOutput(offset: Long) {
        progress.first { it.totalBytes > offset || it.state != ShellProcessState.RUNNING }
    }

    fun attach(shell: SpawnedShell) {
        spawned = shell
    }

    fun headerArrived(header: ShellHeader?) {
        val target =
            synchronized(this) {
                if (headerSeen) {
                    null
                } else {
                    headerSeen = true
                    pgid = header?.pgid
                    uid = header?.uid
                    spawned?.takeIf { stopReason != null }
                }
            }
        headerRead.complete(Unit)
        target?.kill?.invoke(header?.pgid)
    }

    fun stop(reason: ShellProcessState) {
        val target =
            synchronized(this) {
                if (isRunning && stopReason == null) {
                    stopReason = reason
                    spawned?.takeIf { headerSeen }
                } else {
                    null
                }
            }
        target?.kill?.invoke(pgid)
    }

    fun killLeftovers() {
        val group = pgid ?: return
        spawned?.kill?.invoke(group)
    }

    @Synchronized
    fun finish(
        exit: ShellExit,
        nowMs: Long,
    ): Boolean {
        if (!isRunning) return false
        exitCode = exit.code
        failure = exit.reason
        endedAtMs = nowMs
        val state = stopReason ?: if (exit.code == null) ShellProcessState.FAILED else ShellProcessState.EXITED
        progress.update { it.copy(state = state) }
        return true
    }

    fun closeOutput() {
        if (!headerSeen) return
        try {
            spawned?.output?.close()
        } catch (e: IOException) {
            Log.d(TAG, "Output of process $id already closed", e)
        }
    }

    @Synchronized
    fun snapshot(): ShellProcessSnapshot {
        val current = progress.value
        return ShellProcessSnapshot(
            id = id,
            command = launch.command,
            backend = backend.kind,
            elevated = launch.elevated,
            uid = uid,
            state = current.state,
            exitCode = exitCode,
            failure = failure,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            totalBytes = current.totalBytes,
        )
    }
}
