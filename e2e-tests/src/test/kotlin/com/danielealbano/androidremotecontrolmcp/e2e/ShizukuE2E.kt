package com.danielealbano.androidremotecontrolmcp.e2e

import java.io.File
import java.net.URI
import java.security.MessageDigest

object ShizukuE2E {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION = "moe.shizuku.manager.permission.API_V23"
    private const val APK_URL =
        "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
    private const val APK_SHA256 = "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"
    private const val SHELL_IDENTITY = "2000,2000,1004,1007,1011,1015,1028,1078,1079,3001,3002,3003,3006,3009,3011,3012"
    private const val STOCK_SU = "/system/xbin/su"
    private const val PROVIDER = "rikka.shizuku.ShizukuProvider"
    private const val WAIT_MS = 15_000L
    private const val POLL_MS = 500L

    fun install() {
        AndroidContainerSetup.installApk(apk().absolutePath)
    }

    fun uninstall() {
        stop()
        runCatching { AndroidContainerSetup.execAdb("uninstall", PACKAGE) }
    }

    fun startAsShell() = start("$STOCK_SU $SHELL_IDENTITY ${starter()}", "shell")

    fun startAsRoot() = start(starter(), "root")

    fun stop() {
        runCatching { StorageE2E.shell("pkill -9 -f shizuku_server") }
        runCatching { AndroidContainerSetup.execAdb("shell", "am", "force-stop", PACKAGE) }
    }

    fun grant() {
        AndroidContainerSetup.execAdb("shell", "pm", "grant", AndroidContainerSetup.APP_PACKAGE, PERMISSION)
    }

    fun revoke() {
        runCatching {
            AndroidContainerSetup.execAdb("shell", "pm", "revoke", AndroidContainerSetup.APP_PACKAGE, PERMISSION)
        }
    }

    fun providerState(): String {
        val dump = AndroidContainerSetup.execAdb("shell", "dumpsys", "package", AndroidContainerSetup.APP_PACKAGE)
        return when {
            PROVIDER in componentSection(dump, "enabledComponents:") -> "enabled"
            PROVIDER in componentSection(dump, "disabledComponents:") -> "disabled"
            else -> "default"
        }
    }

    fun disableProviderBehindTheApp() {
        AndroidContainerSetup.execAdb("shell", "pm", "disable", "${AndroidContainerSetup.APP_PACKAGE}/$PROVIDER")
    }

    fun isHelperRunning(): Boolean =
        StorageE2E.shell("ps -A -o ARGS").lines().any { it.trim() == "${AndroidContainerSetup.APP_PACKAGE}:shell" }

    fun awaitHelperGone() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline && isHelperRunning()) Thread.sleep(POLL_MS)
        check(!isHelperRunning()) { "The Shizuku shell helper is still running after ${WAIT_MS}ms" }
    }

    private fun start(
        command: String,
        user: String,
    ) {
        stop()
        StorageE2E.shell("$command >/dev/null 2>&1 || true")
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline && !isServerRunningAs(user)) Thread.sleep(POLL_MS)
        check(isServerRunningAs(user)) { "shizuku_server did not start as $user within ${WAIT_MS}ms" }
    }

    private fun componentSection(
        dump: String,
        header: String,
    ): List<String> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return emptyList()
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        return lines
            .drop(start + 1)
            .takeWhile { line -> line.isNotBlank() && line.indexOfFirst { !it.isWhitespace() } > indent }
            .map { it.trim() }
    }

    private fun isServerRunningAs(user: String): Boolean =
        StorageE2E.shell("ps -A -o USER,ARGS").lines().map { it.trim() }.any {
            it.startsWith("$user ") && it.endsWith("shizuku_server")
        }

    private fun starter(): String {
        val base =
            AndroidContainerSetup
                .execAdb("shell", "pm", "path", PACKAGE)
                .trim()
                .removePrefix("package:")
                .removeSuffix("/base.apk")
        return "$base/lib/x86_64/libshizuku.so"
    }

    private fun apk(): File {
        val root = System.getProperty("project.rootDir") ?: error("project.rootDir is not set")
        val file = File(root, "e2e-tests/build/e2e-cache/shizuku-v13.6.0.apk")
        if (!file.isFile || sha256(file) != APK_SHA256) {
            file.parentFile.mkdirs()
            URI(APK_URL).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        check(sha256(file) == APK_SHA256) { "Shizuku APK checksum mismatch: ${sha256(file)}" }
        return file
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
