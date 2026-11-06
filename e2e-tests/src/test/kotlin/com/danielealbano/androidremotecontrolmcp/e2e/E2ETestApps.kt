package com.danielealbano.androidremotecontrolmcp.e2e

import com.danielealbano.androidremotecontrolmcp.e2e.AndroidContainerSetup.execAdb

/**
 * Drives the helper apps installed in the redroid container for E2E tests: the calculator and the compose test
 * app (Compose screen, long list, node screenshot lists, vote bar, text field and WebView activities), plus
 * `uiautomator` node counts and logcat dumps.
 */
object E2ETestApps {
    private const val CALCULATOR_PACKAGE = "com.simplemobiletools.calculator"
    private const val COMPOSE_TEST_PACKAGE = "com.danielealbano.composetestapp"

    /**
     * Launch the calculator app using am start.
     *
     * Uses `am start` with explicit component name instead of `monkey` because
     * the `monkey` command is unavailable in redroid containers (exit 251).
     */
    fun launchCalculator() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "$CALCULATOR_PACKAGE/.activities.MainActivity",
            )
        println("[E2E Setup] launchCalculator result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the compose test app using am start.
     */
    fun launchComposeTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.MainActivity",
            )
        println("[E2E Setup] launchComposeTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Send an intent to the compose test app to update the displayed number.
     *
     * @param number the number to display
     */
    fun sendComposeTestNumber(number: Int) {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "--activity-single-top",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.MainActivity",
                "--ei",
                "number",
                number.toString(),
            )
        println("[E2E Setup] sendComposeTestNumber($number) result: $result")
    }

    /**
     * Force-stops the compose test app so the next launch starts a fresh process. Used to shed the
     * heavy WebView page that E2EWebViewNodeReductionTest leaves loaded in the shared activity,
     * which otherwise blocks the activity's main thread on launch and leaves the WebView's virtual
     * a11y nodes stuck for later simple-page tests.
     */
    fun forceStopComposeTestApp() {
        execAdb("shell", "am", "force-stop", COMPOSE_TEST_PACKAGE)
    }

    /**
     * Launch the long scrollable list test activity (60 rows in a ScrollView) from its top.
     */
    fun launchLongListTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-S",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.LongListActivity",
            )
        println("[E2E Setup] launchLongListTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the node screenshot list (40 view rows and two tall blocks in a ScrollView) from a fresh process, at its
     * top.
     */
    fun launchNodeScreenshotTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-S",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.NodeScreenshotActivity",
            )
        println("[E2E Setup] launchNodeScreenshotTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the node screenshot Compose column (4 rows, then a block cut at the bottom of the screen) from a fresh
     * process, at its top.
     */
    fun launchNodeScreenshotComposeTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-S",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.NodeScreenshotComposeActivity",
            )
        println("[E2E Setup] launchNodeScreenshotComposeTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the vote bar activity (a node with only the custom actions "Upvote" and "Downvote", and a "Votes: <n>"
     * counter) from a fresh process, so the counter starts at 0.
     */
    fun launchCustomActionsTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-S",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.CustomActionsActivity",
            )
        println("[E2E Setup] launchCustomActionsTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the text field test activity in a new task, so its field starts empty: [field] is "compose" for a
     * Compose text field or "view" for a classic EditText. The previous activity is finished, not its process killed:
     * killing a process whose field holds an input session can leave the accessibility input method without one for
     * the next field.
     */
    fun launchTextFieldTestApp(field: String) {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "--activity-clear-task",
                "--es",
                "field",
                field,
                "-n",
                "$COMPOSE_TEST_PACKAGE/.TextFieldActivity",
            )
        println("[E2E Setup] launchTextFieldTestApp($field) result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the WebView test activity using am start.
     */
    fun launchWebViewTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.WebViewActivity",
            )
        println("[E2E Setup] launchWebViewTestApp result: $result")
        Thread.sleep(3_000)
    }

    /**
     * Launch the WebView test activity with the large, content-heavy page
     * (hundreds of articles) used to exercise WebView node collapsing.
     */
    fun launchHeavyWebViewTestApp() {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "-W",
                "--es",
                "content",
                "heavy",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.WebViewActivity",
            )
        println("[E2E Setup] launchHeavyWebViewTestApp result: $result")
        Thread.sleep(3_000)
    }

    /**
     * Counts the accessibility nodes currently on screen via `uiautomator dump`. This is the raw,
     * un-collapsed node count (every node the platform exposes), used as the baseline against the
     * collapsed `get_screen_state` output.
     *
     * @return the number of `<node` elements in the dump; a dump or read that fails throws.
     */
    fun uiAutomatorNodeCount(): Int {
        execAdb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
        val xml = execAdb("shell", "cat", "/sdcard/uidump.xml")
        return Regex("<node ").findAll(xml).count()
    }

    /**
     * Counts on-screen nodes that would survive `get_screen_state`'s keep-filter BEFORE the WebView
     * merge: a node is kept if it has non-empty text / content-desc / resource-id, or is clickable /
     * long-clickable / scrollable. Comparing the collapsed `get_screen_state` count against THIS
     * baseline (rather than the raw total) isolates the merge's contribution from the structural-only
     * filtering that `get_screen_state` always applies. The keep-filter also keeps a node with labelled
     * accessibility actions, which a `uiautomator` dump does not show: the baseline holds only while the
     * page's web nodes carry no labelled actions.
     */
    fun uiAutomatorKeptNodeCount(): Int {
        execAdb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
        val xml = execAdb("shell", "cat", "/sdcard/uidump.xml")
        return NODE_TAG_REGEX.findAll(xml).count { match ->
            val tag = match.value
            attrNonEmpty(tag, "text") ||
                attrNonEmpty(tag, "content-desc") ||
                attrNonEmpty(tag, "resource-id") ||
                attrTrue(tag, "clickable") ||
                attrTrue(tag, "long-clickable") ||
                attrTrue(tag, "scrollable")
        }
    }

    private val NODE_TAG_REGEX = Regex("<node\\b[^>]*>")

    private fun attrNonEmpty(
        tag: String,
        name: String,
    ): Boolean {
        val match = Regex("\\s$name=\"([^\"]*)\"").find(tag) ?: return false
        return match.groupValues[1].isNotEmpty()
    }

    private fun attrTrue(
        tag: String,
        name: String,
    ): Boolean = Regex("\\s$name=\"true\"").containsMatchIn(tag)

    /**
     * Send an intent to the WebView test activity to update the displayed number.
     *
     * @param number the number to display
     */
    fun sendWebViewTestNumber(number: Int) {
        val result =
            execAdb(
                "shell",
                "am",
                "start",
                "--activity-single-top",
                "-n",
                "$COMPOSE_TEST_PACKAGE/.WebViewActivity",
                "--ei",
                "number",
                number.toString(),
            )
        println("[E2E Setup] sendWebViewTestNumber($number) result: $result")
    }

    /**
     * Dump logcat lines from the compose test app for diagnostics.
     *
     * @return recent logcat lines matching the ComposeTestApp tag
     */
    fun dumpComposeTestAppLogs(): String =
        try {
            execAdb("shell", "logcat", "-d", "-s", "ComposeTestApp:I")
        } catch (e: Exception) {
            "Failed to dump logcat: ${e.message}"
        }

    /**
     * Dump logcat lines from the WebView test activity for diagnostics.
     *
     * @return recent logcat lines matching the WebViewTestApp tag
     */
    fun dumpWebViewTestAppLogs(): String =
        try {
            execAdb("shell", "logcat", "-d", "-s", "WebViewTestApp:I")
        } catch (e: Exception) {
            "Failed to dump logcat: ${e.message}"
        }
}
