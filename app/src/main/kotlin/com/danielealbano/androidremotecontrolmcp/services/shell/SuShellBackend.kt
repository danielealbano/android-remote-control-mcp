package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "MCP:SuShellBackend"
private const val SU_BINARY = "su"
private const val SU_PROBE_TIMEOUT_SECONDS = 15L
private const val SU_KILL_TIMEOUT_SECONDS = 15L
private const val ROOT_UID = "0"
private val FALLBACK_SU_DIRECTORIES = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")

sealed interface SuProbeResult {
    data object Works : SuProbeResult

    data class Fails(
        val reason: String,
    ) : SuProbeResult
}

interface SuShell : ShellBackend {
    fun locate(): String?

    suspend fun probe(): SuProbeResult
}

@Singleton
class SuShellBackend internal constructor(
    private val searchDirectories: () -> List<String>,
    private val probeTimeoutSeconds: Long,
) : SuShell {
    @Inject
    constructor() : this(::defaultSuDirectories, SU_PROBE_TIMEOUT_SECONDS)

    override val kind = ShellBackendKind.SU

    override fun locate(): String? =
        searchDirectories()
            .map { File(it, SU_BINARY) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath

    override suspend fun spawn(
        processId: Int,
        script: String,
    ): SpawnedShell =
        withContext(Dispatchers.IO) {
            val su = locate() ?: throw IllegalStateException("su was not found")
            spawnProcess(listOf(su, "-c", script)) { pgid -> killAsRoot(su, pgid) }
        }

    override suspend fun probe(): SuProbeResult =
        withContext(Dispatchers.IO) {
            val su = locate()
            if (su == null) SuProbeResult.Fails("su was not found") else runProbe(su)
        }

    override suspend fun release(processId: Int) = Unit

    override fun releaseAll() = Unit

    private fun runProbe(su: String): SuProbeResult =
        try {
            val process = ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(probeTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                SuProbeResult.Fails("su did not answer within $probeTimeoutSeconds seconds")
            } else {
                val output =
                    process
                        .inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                if (process.exitValue() == 0 && output == ROOT_UID) {
                    SuProbeResult.Works
                } else {
                    SuProbeResult.Fails("su exited with ${process.exitValue()}: ${output.ifEmpty { "no output" }}")
                }
            }
        } catch (e: IOException) {
            SuProbeResult.Fails(e.message ?: "su could not start")
        }
}

internal fun defaultSuDirectories(): List<String> {
    val path =
        System
            .getenv("PATH")
            .orEmpty()
            .split(':')
            .filter { it.isNotEmpty() }
    return (path + FALLBACK_SU_DIRECTORIES).distinct()
}

private fun killAsRoot(
    su: String,
    pgid: Int,
) {
    thread(isDaemon = true, name = "shell-su-kill") {
        try {
            val process = ProcessBuilder(su, "-c", "kill -9 -$pgid").redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(SU_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (e: IOException) {
            Log.w(TAG, "su could not kill process group $pgid", e)
        }
    }
}
