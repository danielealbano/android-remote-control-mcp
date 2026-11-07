package com.danielealbano.androidremotecontrolmcp.services.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AppShellBackend internal constructor(
    private val shellPath: String,
    private val killGroup: (Int) -> Unit,
) : ShellBackend {
    @Inject
    constructor() : this(ShellScriptBuilder.ANDROID_SHELL_PATH, ::killProcessGroup)

    override val kind = ShellBackendKind.APP

    override suspend fun spawn(
        processId: Int,
        script: String,
    ): SpawnedShell = withContext(Dispatchers.IO) { spawnProcess(listOf(shellPath, "-c", script), killGroup) }

    override suspend fun release(processId: Int) = Unit

    override fun releaseAll() = Unit
}
