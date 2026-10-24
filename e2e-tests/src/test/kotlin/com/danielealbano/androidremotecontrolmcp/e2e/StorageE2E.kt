package com.danielealbano.androidremotecontrolmcp.e2e

import java.util.Base64

/**
 * Shared storage-E2E infrastructure: MediaStore fixture seeding, media scanning,
 * READ_MEDIA_* permission control, builtin allow-write/allow-delete toggles,
 * download settings, app-process lifecycle helpers, and MediaStore row injection.
 *
 * Files seeded via adb shell are NON-OWNED from the app's perspective; files created
 * through the app's MCP tools are OWNED. Seeded files become visible to MediaStore
 * only after [scanVolume]/[scanPath], in the collection implied by their extension.
 *
 * Fixture names MUST NOT contain double quotes, backticks, or '$' (double-quote shell quoting).
 */
object StorageE2E {
    const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.gms.debug"
    const val PERM_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val PERM_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val PERM_AUDIO = "android.permission.READ_MEDIA_AUDIO"

    /** Platform permission backing Android 14+ partial photo access (API 34+ only). */
    const val PERM_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private const val SDCARD = "/storage/emulated/0"
    private const val E2E_ACTION_BASE = "com.danielealbano.androidremotecontrolmcp.debug"
    private const val E2E_CONFIG_RECEIVER =
        "$APP_PACKAGE/com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver"

    /**
     * Runs a shell command on the device. `adb shell` re-joins its arguments with
     * spaces before the device shell parses them, so the whole command is wrapped
     * in single quotes (with embedded single quotes escaped) to reach the device's
     * `sh -c` as ONE argument.
     */
    fun shell(cmd: String): String {
        val quoted = "'" + cmd.replace("'", "'\\''") + "'"
        return AndroidContainerSetup.execAdb("shell", "sh", "-c", quoted)
    }

    fun grantMediaPermission(permission: String) {
        AndroidContainerSetup.execAdb("shell", "pm", "grant", APP_PACKAGE, permission)
    }

    fun grantAllMediaPermissions() {
        grantMediaPermission(PERM_IMAGES)
        grantMediaPermission(PERM_VIDEO)
        grantMediaPermission(PERM_AUDIO)
    }

    /**
     * Revokes a granted runtime permission. WARNING: this KILLS the app process.
     * Callers MUST afterwards call [waitForAppProcessDeath], then
     * [restartServerAndRefreshClient].
     */
    fun revokeMediaPermission(permission: String) {
        AndroidContainerSetup.execAdb("shell", "pm", "revoke", APP_PACKAGE, permission)
    }

    /** Seeds a file with the given text content at an /sdcard-relative path (parents created). */
    fun seedFile(relativePath: String, content: String) {
        val path = "$SDCARD/$relativePath"
        val dir = path.substringBeforeLast('/')
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray())
        shell("mkdir -p \"$dir\" && echo $b64 | base64 -d > \"$path\"")
    }

    /** Seeds [count] one-byte files named <prefix>NNN.jpg under an /sdcard-relative directory. */
    fun seedBulk(dirRelativePath: String, count: Int, prefix: String = "bulk") {
        val dir = "$SDCARD/$dirRelativePath"
        val d = "\$"
        shell(
            "mkdir -p \"$dir\" && i=0; while [ ${d}i -lt $count ]; do " +
                "printf x > \"$dir/$prefix${d}(printf %03d ${d}i).jpg\"; i=${d}((i+1)); done",
        )
    }

    /** Removes a single seeded file from disk (rm -f: best-effort, no recursion). */
    fun removeFromDisk(relativePath: String) {
        shell("rm -f \"$SDCARD/$relativePath\"")
    }

    /** Removes a seeded fixture directory tree. ONLY for paths created by this suite. */
    fun removeFixtureTree(relativePath: String) {
        require(relativePath.isNotBlank() && !relativePath.contains("..")) { "unsafe path" }
        shell("rm -rf \"$SDCARD/$relativePath\"")
    }

    /**
     * Triggers a MediaStore scan. Mechanism verified empirically against redroid 13 and 14
     * per the plan's procedure (scan_volume primary; scan_file per path; legacy
     * broadcast last). The verified mechanism is kept, the others removed.
     */
    fun scanVolume() {
        AndroidContainerSetup.execAdb(
            "shell", "content", "call", "--uri", "content://media/none",
            "--method", "scan_volume", "--arg", "external_primary",
        )
    }

    fun scanPath(relativePath: String) {
        AndroidContainerSetup.execAdb(
            "shell", "content", "call", "--uri", "content://media/none",
            "--method", "scan_file", "--arg", "$SDCARD/$relativePath",
        )
    }

    /** Sets builtin location allow-write/allow-delete via the E2E config broadcast. */
    fun configureStorageLocation(locationId: String, allowWrite: Boolean, allowDelete: Boolean) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", "$E2E_ACTION_BASE.E2E_CONFIGURE",
            "-n", E2E_CONFIG_RECEIVER,
            "--es", "storage_location_id", locationId,
            "--ez", "storage_allow_write", allowWrite.toString(),
            "--ez", "storage_allow_delete", allowDelete.toString(),
        )
        Thread.sleep(1_000)
    }

    /** Sets download timeout + HTTP-download allowance via the E2E config broadcast. */
    fun configureDownloadSettings(timeoutSeconds: Int, allowHttp: Boolean) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", "$E2E_ACTION_BASE.E2E_CONFIGURE",
            "-n", E2E_CONFIG_RECEIVER,
            "--ei", "download_timeout_seconds", timeoutSeconds.toString(),
            "--ez", "allow_http_downloads", allowHttp.toString(),
        )
        Thread.sleep(1_000)
    }

    /** Sets "Block Local Network Downloads" via the E2E config broadcast. */
    fun configureBlockLocalNetworkDownloads(block: Boolean) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", "$E2E_ACTION_BASE.E2E_CONFIGURE",
            "-n", E2E_CONFIG_RECEIVER,
            "--ez", "block_local_network_downloads", block.toString(),
        )
        Thread.sleep(1_000)
    }

    /** Inserts a MediaStore row with IS_PENDING=1 (no backing content written). */
    fun insertPendingRow(displayName: String, relativePathWithSlash: String) {
        AndroidContainerSetup.execAdb(
            "shell", "content", "insert", "--uri", "content://media/external_primary/file",
            "--bind", "_display_name:s:$displayName",
            "--bind", "relative_path:s:$relativePathWithSlash",
            "--bind", "is_pending:i:1",
        )
    }

    /** Inserts a second MediaStore row with the same display name + relative path. */
    fun insertDuplicateRow(displayName: String, relativePathWithSlash: String) {
        AndroidContainerSetup.execAdb(
            "shell", "content", "insert", "--uri", "content://media/external_primary/file",
            "--bind", "_display_name:s:$displayName",
            "--bind", "relative_path:s:$relativePathWithSlash",
        )
    }

    /** Polls until the app process is gone (used after [revokeMediaPermission]). */
    fun waitForAppProcessDeath(timeoutMs: Long = 15_000L) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val pid = try {
                shell("pidof $APP_PACKAGE || true")
            } catch (_: Exception) {
                ""
            }
            if (pid.isBlank()) return
            Thread.sleep(500)
        }
        error("App process still alive ${timeoutMs}ms after permission revoke")
    }

    /**
     * Restarts the MCP server and REFRESHES the shared cached client
     * (SharedAndroidContainer.mcpClient), so both this suite and any test class
     * running afterwards see a live session. Returns the refreshed client.
     */
    fun restartServerAndRefreshClient(): McpClient {
        AndroidContainerSetup.startMcpServer()
        AndroidContainerSetup.waitForServerReady(SharedAndroidContainer.mcpServerUrl)
        return SharedAndroidContainer.refreshMcpClient()
    }
}
