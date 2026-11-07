package com.danielealbano.androidremotecontrolmcp.e2e

object DeviceUiE2E {
    private const val DUMP_PATH = "/sdcard/e2e_device_ui.xml"
    private const val WAIT_MS = 20_000L
    private const val POLL_MS = 500L
    private const val SWIPE_MS = "300"
    private val BOUNDS = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")

    fun expandNotifications() {
        AndroidContainerSetup.execAdb("shell", "cmd", "statusbar", "expand-notifications")
    }

    fun collapseNotifications() {
        AndroidContainerSetup.execAdb("shell", "cmd", "statusbar", "collapse")
    }

    fun tapWhenVisible(text: String) {
        val bounds = awaitVisible(text)
        val x = "${(bounds[0] + bounds[2]) / 2}"
        val y = "${(bounds[1] + bounds[3]) / 2}"
        AndroidContainerSetup.execAdb("shell", "input", "tap", x, y)
    }

    fun swipeAwayWhenVisible(text: String) {
        val bounds = awaitVisible(text)
        val y = "${(bounds[1] + bounds[3]) / 2}"
        AndroidContainerSetup.execAdb("shell", "input", "swipe", "${bounds[0] + 1}", y, "${bounds[2] - 1}", y, SWIPE_MS)
    }

    fun awaitVisible(text: String): List<Int> {
        val deadline = System.currentTimeMillis() + WAIT_MS
        var bounds = findBounds(text)
        while (bounds == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            bounds = findBounds(text)
        }
        return checkNotNull(bounds) { "'$text' did not appear on screen within ${WAIT_MS}ms" }
    }

    private fun findBounds(text: String): List<Int>? {
        val pattern = Regex("<node[^>]*(?:text|content-desc)=\"${Regex.escape(text)}\"[^>]*>", RegexOption.IGNORE_CASE)
        val node = dump()?.let { pattern.find(it)?.value }
        return node?.let {
            BOUNDS
                .find(it)
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
        }
    }

    private fun dump(): String? =
        runCatching {
            AndroidContainerSetup.execAdb("shell", "uiautomator", "dump", DUMP_PATH)
            AndroidContainerSetup.execAdb("shell", "cat", DUMP_PATH)
        }.getOrNull()
}
