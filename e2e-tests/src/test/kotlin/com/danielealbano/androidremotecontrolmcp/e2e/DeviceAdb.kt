package com.danielealbano.androidremotecontrolmcp.e2e

/**
 * The attached phone: the first `adb devices` entry in state `device` that is neither an `emulator-*` nor a
 * redroid container (`ro.boot.redroid_width` is set by the container's boot arguments). USB and `adb connect`
 * phones both qualify.
 */
object DeviceAdb {
    private const val INSTALL_TIMEOUT_S = 180L

    fun attachedSerial(): String? =
        AndroidContainerSetup
            .runProcess("adb", "devices")
            .lines()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" && !it[0].startsWith("emulator-") }
            .map { it[0] }
            .firstOrNull { !isRedroid(it) }

    fun install(
        serial: String,
        apkPath: String,
    ) {
        val result =
            AndroidContainerSetup.runProcess(
                "adb", "-s", serial, "install", "-r", apkPath,
                timeoutSeconds = INSTALL_TIMEOUT_S,
            )
        check(result.contains("Success")) { "APK installation failed on $serial: $result" }
    }

    private fun isRedroid(serial: String): Boolean =
        AndroidContainerSetup.execAdbOn(serial, "shell", "getprop", "ro.boot.redroid_width").isNotEmpty()
}
