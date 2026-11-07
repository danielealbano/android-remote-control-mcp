package com.danielealbano.androidremotecontrolmcp.services.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.InputStream
import kotlin.concurrent.thread

private const val TAG = "MCP:ShellBackend"

enum class ShellBackendKind(
    val wireName: String,
) {
    APP("app"),
    SHIZUKU("shizuku"),
    SU("su"),
}

data class ShellExit(
    val code: Int?,
    val reason: String? = null,
)

class SpawnedShell(
    val output: InputStream,
    val exit: Deferred<ShellExit>,
    val kill: (pgid: Int?) -> Unit,
)

interface ShellBackend {
    val kind: ShellBackendKind

    suspend fun spawn(
        processId: Int,
        script: String,
    ): SpawnedShell

    suspend fun release(processId: Int)

    fun releaseAll()
}

internal fun spawnProcess(
    argv: List<String>,
    killGroup: (Int) -> Unit,
): SpawnedShell {
    val process = ProcessBuilder(argv).redirectErrorStream(true).start()
    process.outputStream.close()
    val exit = CompletableDeferred<ShellExit>()
    thread(isDaemon = true, name = "shell-wait") { exit.complete(ShellExit(process.waitFor())) }
    return SpawnedShell(process.inputStream, exit) { pgid ->
        if (pgid != null) killGroup(pgid) else process.destroyForcibly()
    }
}

internal fun killProcessGroup(pgid: Int) {
    try {
        Os.kill(-pgid, OsConstants.SIGKILL)
    } catch (e: ErrnoException) {
        Log.d(TAG, "Process group $pgid not killed", e)
    }
}
