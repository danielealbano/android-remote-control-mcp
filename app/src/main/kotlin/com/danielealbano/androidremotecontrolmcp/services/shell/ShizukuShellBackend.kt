package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MCP:ShizukuShellBackend"
private const val PROCESS_SUFFIX = "shell"
private const val SERVICE_TAG = "shell"
private val BIND_TIMEOUT = 10.seconds

internal class ShellServiceBinding(
    val args: Shizuku.UserServiceArgs,
    private val onLost: (ShellServiceBinding) -> Unit,
) : ServiceConnection {
    val connected = CompletableDeferred<IShellService>()

    @Volatile
    var service: IShellService? = null

    override fun onServiceConnected(
        name: ComponentName?,
        binder: IBinder?,
    ) {
        val bound = binder?.takeIf { it.pingBinder() }?.let(IShellService.Stub::asInterface) ?: return
        service = bound
        connected.complete(bound)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        onLost(this)
    }
}

private inline fun useShizuku(
    action: String,
    block: () -> Unit,
): Boolean = runCatching(block).onFailure { Log.w(TAG, "Shizuku $action failed", it) }.isSuccess

internal class ShellExitCallback(
    private val exits: ConcurrentHashMap<Int, CompletableDeferred<ShellExit>>,
) : IShellProcessCallback.Stub() {
    override fun onExit(
        processId: Int,
        exitCode: Int,
    ) {
        exits[processId]?.complete(ShellExit(exitCode))
    }
}

@Singleton
class ShizukuShellBackend internal constructor(
    private val packageName: String,
    private val api: ShizukuApi,
    private val openOutput: (ParcelFileDescriptor) -> InputStream,
    private val bindTimeout: Duration,
) : ShellBackend {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        api: ShizukuApi,
    ) : this(context.packageName, api, { ParcelFileDescriptor.AutoCloseInputStream(it) }, BIND_TIMEOUT)

    override val kind = ShellBackendKind.SHIZUKU

    private val lock = Mutex()
    private val exits = ConcurrentHashMap<Int, CompletableDeferred<ShellExit>>()
    private val callback = ShellExitCallback(exits)
    private var generation = 0

    @Volatile
    private var binding: ShellServiceBinding? = null

    override suspend fun spawn(
        processId: Int,
        script: String,
    ): SpawnedShell =
        lock.withLock {
            val service = bound()
            val exit = CompletableDeferred<ShellExit>()
            exits[processId] = exit
            check(binding?.service === service) { "The shell helper was stopped" }
            val descriptor = startOn(service, processId, script)
            SpawnedShell(openOutput(descriptor), exit) { _ -> killQuietly(service, processId) }
        }

    override suspend fun release(processId: Int) {
        lock.withLock {
            exits.remove(processId)
            if (exits.isEmpty()) binding?.let(::drop)
        }
    }

    override fun releaseAll() {
        binding?.let(::drop)
        failPending("The server stopped")
    }

    private suspend fun bound(): IShellService {
        binding?.service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        binding?.let { stale ->
            onLost(stale)
            drop(stale)
        }
        val fresh = ShellServiceBinding(newArgs(), ::onLost)
        binding = fresh
        if (!useShizuku("bind") { api.bindUserService(fresh.args, fresh) }) {
            binding = null
            throw IllegalStateException("Shizuku could not start the shell helper")
        }
        val service = withTimeoutOrNull(bindTimeout) { fresh.connected.await() }
        if (service == null || binding !== fresh) {
            drop(fresh)
            val reason =
                if (service == null) {
                    "Shizuku did not start the shell helper within ${bindTimeout.inWholeSeconds} seconds"
                } else {
                    "The shell helper was stopped while it was starting"
                }
            throw IllegalStateException(reason)
        }
        return service
    }

    private fun newArgs(): Shizuku.UserServiceArgs {
        generation += 1
        return Shizuku
            .UserServiceArgs(ComponentName(packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix(PROCESS_SUFFIX)
            .tag("$SERVICE_TAG-$generation")
            .version(BuildConfig.VERSION_CODE)
            .debuggable(false)
    }

    private fun onLost(lost: ShellServiceBinding) {
        if (binding !== lost) return
        binding = null
        failPending("Shizuku stopped")
    }

    private fun drop(stale: ShellServiceBinding) {
        if (binding === stale) binding = null
        useShizuku("unbind") { api.unbindUserService(stale.args, stale) }
    }

    private suspend fun startOn(
        service: IShellService,
        processId: Int,
        script: String,
    ): ParcelFileDescriptor =
        withContext(Dispatchers.IO) {
            try {
                service.start(processId, script, callback)
            } catch (e: RemoteException) {
                throw IllegalStateException("Shizuku could not start the command: ${e.message}", e)
            }
        } ?: throw IllegalStateException("Shizuku returned no output stream")

    private fun killQuietly(
        service: IShellService,
        processId: Int,
    ) {
        try {
            service.kill(processId)
        } catch (e: RemoteException) {
            Log.w(TAG, "Shizuku could not stop process $processId", e)
        }
    }

    private fun failPending(reason: String) {
        val pending = exits.values.toList()
        exits.clear()
        pending.forEach { it.complete(ShellExit(null, reason)) }
    }
}
