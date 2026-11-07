package com.danielealbano.androidremotecontrolmcp.e2e

object DeviceUiE2E {
    private const val DUMP_PATH = "/sdcard/e2e_device_ui.xml"
    private const val WAIT_MS = 20_000L
    private const val POLL_MS = 500L
    private const val SWIPE_MS = "300"
    private val BOUNDS = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
    private val SIZE = Regex("(\\d+)x(\\d+)")

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

    /** Swipes from the text to the right screen edge: a swipe across the text alone is too short to dismiss its row. */
    fun swipeAwayWhenVisible(text: String) {
        val bounds = awaitVisible(text)
        val y = "${(bounds[1] + bounds[3]) / 2}"
        val end = "${screenWidth() - 1}"
        AndroidContainerSetup.execAdb("shell", "input", "swipe", "${bounds[0] + 1}", y, end, y, SWIPE_MS)
    }

    fun awaitNotificationPosted(tag: String) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!isNotificationPosted(tag) && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        check(isNotificationPosted(tag)) { "No notification tagged '$tag' within ${WAIT_MS}ms" }
    }

    private fun isNotificationPosted(tag: String): Boolean =
        AndroidContainerSetup.execAdb("shell", "dumpsys", "notification").contains("tag=$tag ")

    private fun screenWidth(): Int {
        val size = AndroidContainerSetup.execAdb("shell", "wm", "size")
        val match = checkNotNull(SIZE.findAll(size).lastOrNull()) { "Unexpected 'wm size' output: $size" }
        return match.groupValues[1].toInt()
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
