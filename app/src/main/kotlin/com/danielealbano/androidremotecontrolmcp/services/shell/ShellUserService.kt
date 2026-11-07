package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import java.io.IOException
import kotlin.system.exitProcess

private const val TAG = "MCP:ShellUserService"

class ShellUserService(
    context: Context,
) : IShellService.Stub() {
    private val appUid =
        context.packageManager.getPackageUid(context.packageName, PackageManager.PackageInfoFlags.of(0))
    private val host = ShellProcessHost(ShellScriptBuilder.ANDROID_SHELL_PATH, ::killProcessGroup)

    init {
        Runtime.getRuntime().addShutdownHook(Thread { host.killAll() })
    }

    override fun start(
        processId: Int,
        script: String,
        callback: IShellProcessCallback,
    ): ParcelFileDescriptor {
        enforceCaller()
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            host.start(processId, script, ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { code ->
                deliverExit(callback, processId, code)
            }
        } catch (e: IOException) {
            pipe.forEach { it.close() }
            throw IllegalStateException("Could not start sh: ${e.message}", e)
        }
        return pipe[0]
    }

    override fun kill(processId: Int) {
        enforceCaller()
        host.kill(processId)
    }

    override fun destroy() {
        host.killAll()
        exitProcess(0)
    }

    private fun enforceCaller() {
        val caller = Binder.getCallingUid()
        if (caller != appUid) throw SecurityException("Caller uid $caller is not the app")
    }

    internal fun deliverExit(
        callback: IShellProcessCallback,
        processId: Int,
        code: Int,
    ): Boolean =
        try {
            callback.onExit(processId, code)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "The app is gone; stopping its processes", e)
            false
        }
}
