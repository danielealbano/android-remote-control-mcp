package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MCP:ShellProcessManager"
private const val MILLIS_PER_MINUTE = 60_000L
private const val SERVER_STOPPED = "The server stopped"
private const val SERVER_NOT_RUNNING = "The MCP server is not running; the command did not start"

interface ShellProcessManager {
    fun open()

    suspend fun startAndAwait(
        launch: ShellLaunch,
        config: ShellConfig,
        waitMs: Long,
        maxBytes: Int,
    ): ShellProcessPage

    suspend fun page(
        id: Int,
        offset: Long,
        waitMs: Long,
        maxBytes: Int,
    ): ShellProcessPage

    suspend fun killAndAwait(
        id: Int,
        settleMs: Long,
    ): ShellProcessSnapshot

    fun list(): List<ShellProcessSnapshot>

    fun shutdown()
}

@Singleton
class ShellProcessManagerImpl internal constructor(
    private val appBackend: ShellBackend,
    private val selectElevated: () -> ShellBackend,
    private val elevatedBackends: List<ShellBackend>,
    serverLog: ServerLogRepository,
    private val currentConfig: suspend () -> ShellConfig,
    private val scriptBuilder: ShellScriptBuilder,
    ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : ShellProcessManager {
    @Inject
    constructor(
        appBackend: AppShellBackend,
        selector: ElevatedShellSelector,
        serverLog: ServerLogRepository,
        settingsRepository: SettingsRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        appBackend,
        selector::select,
        selector.backends,
        serverLog,
        { settingsRepository.getServerConfig().shellConfig },
        ShellScriptBuilder(),
        ioDispatcher,
        System::currentTimeMillis,
    )

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                ioDispatcher +
                CoroutineExceptionHandler { _, error -> Log.e(TAG, "A shell process watcher failed", error) },
        )
    private val table = ShellProcessTable()
    private val audit = ShellAudit(serverLog)
    private val watcher = ShellProcessWatcher(scope, table, audit, currentConfig, clock)

    @Volatile
    private var accepting = false

    override fun open() {
        accepting = true
    }

    override suspend fun startAndAwait(
        launch: ShellLaunch,
        config: ShellConfig,
        waitMs: Long,
        maxBytes: Int,
    ): ShellProcessPage {
        val process = start(launch, config)
        try {
            if (waitMs > 0) withTimeoutOrNull(waitMs) { process.awaitEnd() }
        } catch (e: CancellationException) {
            process.stop(ShellProcessState.KILLED)
            throw e
        }
        return pageOf(process, 0, maxBytes)
    }

    override suspend fun page(
        id: Int,
        offset: Long,
        waitMs: Long,
        maxBytes: Int,
    ): ShellProcessPage {
        val process = table.get(id)
        val total = process.buffer.totalBytes()
        if (offset !in 0..total) {
            throw McpToolException.InvalidParams("Parameter 'offset' must be between 0 and $total, got: $offset")
        }
        if (waitMs > 0) withTimeoutOrNull(waitMs) { process.awaitOutput(offset) }
        return pageOf(process, offset, maxBytes)
    }

    override suspend fun killAndAwait(
        id: Int,
        settleMs: Long,
    ): ShellProcessSnapshot {
        val process = table.get(id)
        process.stop(ShellProcessState.KILLED)
        withTimeoutOrNull(settleMs) { process.awaitEnd() }
        return process.snapshot()
    }

    override fun list(): List<ShellProcessSnapshot> = table.all().map { it.snapshot() }

    override fun shutdown() {
        accepting = false
        val processes = table.clear()
        processes.forEach { it.stop(ShellProcessState.KILLED) }
        (elevatedBackends + appBackend).forEach { it.releaseAll() }
        scope.coroutineContext.cancelChildren()
        processes.forEach { watcher.end(it, ShellExit(null, SERVER_STOPPED)) }
    }

    private suspend fun start(
        launch: ShellLaunch,
        config: ShellConfig,
    ): ShellProcess {
        val process = register(launch, config)
        try {
            withContext(NonCancellable) { spawnAndWatch(process) }
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            process.stop(ShellProcessState.KILLED)
            throw e
        }
        return process
    }

    private fun register(
        launch: ShellLaunch,
        config: ShellConfig,
    ): ShellProcess =
        runCatching {
            if (!accepting) throw McpToolException.ActionFailed(SERVER_NOT_RUNNING)
            val backend = if (launch.elevated) selectElevated() else appBackend
            table.register(launch, backend, config, clock(), audit::starting)
        }.onFailure { audit.notStarted(launch, it.message.orEmpty()) }.getOrThrow()

    private suspend fun spawnAndWatch(process: ShellProcess) {
        val launch = process.launch
        val script = scriptBuilder.build(launch.command, launch.workdir, launch.envs)
        val spawned =
            runCatching { process.backend.spawn(process.id, script) }.getOrElse { throw failStart(process, it) }
        process.attach(spawned)
        if (!accepting) process.stop(ShellProcessState.KILLED)
        process.timeoutJob =
            scope.launch {
                delay(launch.timeoutMinutes * MILLIS_PER_MINUTE)
                process.stop(ShellProcessState.TIMED_OUT)
            }
        watcher.watch(process, spawned)
    }

    private suspend fun failStart(
        process: ShellProcess,
        error: Throwable,
    ): McpToolException {
        val message = "The command could not start through ${process.backend.kind.wireName}: ${error.message}"
        if (process.finish(ShellExit(null, error.message ?: error.javaClass.simpleName), clock())) {
            audit.failedToStart(process, message)
        }
        process.backend.release(process.id)
        table.trimFinished(currentConfig().limit(ShellLimit.MAX_FINISHED_PROCESSES))
        return McpToolException.ActionFailed(message, error)
    }

    private fun pageOf(
        process: ShellProcess,
        offset: Long,
        maxBytes: Int,
    ): ShellProcessPage {
        val snapshot = process.snapshot()
        val chunk = process.buffer.read(offset, maxBytes, snapshot.state == ShellProcessState.RUNNING)
        return ShellProcessPage(snapshot, chunk)
    }
}
