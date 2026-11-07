package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MCP:ShellScratch"
private const val DIRECTORY_NAME = "shell"
private const val MKDIR_SCRIPT = "umask 0007; mkdir -p -- \"\$1\""
private const val MKDIR_TIMEOUT_SECONDS = 10L

interface ShellScratchDirectory {
    fun path(): File?

    fun reset(): File?
}

@Singleton
class ShellScratchDirectoryImpl internal constructor(
    private val cacheDir: () -> File?,
    private val shellPath: String,
) : ShellScratchDirectory {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this({ context.externalCacheDir }, ShellScriptBuilder.ANDROID_SHELL_PATH)

    override fun path(): File? = cacheDir()?.let { File(it, DIRECTORY_NAME) }

    override fun reset(): File? =
        path()?.also {
            deleteWithoutFollowingLinks(it.toPath())
            createSharedDirectory(it)
        }

    private fun createSharedDirectory(directory: File) {
        try {
            val process =
                ProcessBuilder(shellPath, "-c", MKDIR_SCRIPT, "sh", directory.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            process.outputStream.close()
            if (!process.waitFor(MKDIR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (e: IOException) {
            Log.w(TAG, "The shell scratch folder could not be created", e)
        }
    }
}

internal fun deleteWithoutFollowingLinks(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    try {
        Files.walkFileTree(root, DeletingVisitor)
    } catch (e: IOException) {
        Log.w(TAG, "The shell scratch folder could not be emptied", e)
    }
}

private object DeletingVisitor : SimpleFileVisitor<Path>() {
    override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        deleteQuietly(file)
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(
        file: Path,
        exc: IOException,
    ): FileVisitResult {
        Log.w(TAG, "Cannot read $file in the shell scratch folder; left in place", exc)
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(
        dir: Path,
        exc: IOException?,
    ): FileVisitResult {
        deleteQuietly(dir)
        return FileVisitResult.CONTINUE
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.delete(path)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot delete $path in the shell scratch folder; left in place", e)
        }
    }
}
