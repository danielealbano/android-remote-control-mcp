package com.danielealbano.androidremotecontrolmcp.e2e

import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.Device
import com.github.dockerjava.api.model.LogConfig
import com.github.dockerjava.api.model.Volume
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Manages the redroid container lifecycle for E2E tests.
 *
 * Responsibilities:
 * - Create and configure the redroid container with kernel module setup
 * - Wait for boot completion via host-side ADB connection
 * - Install APK and configure permissions via host-side adb commands
 * - Start the MCP server and wait for it to be ready
 */
object AndroidContainerSetup {

    private const val DOCKER_IMAGE = "redroid/redroid:14.0.0-latest"
    private const val ADB_PORT = 5555
    private const val MCP_DEFAULT_PORT = 8080
    private const val PROCESS_TIMEOUT_SECONDS = 30L
    private const val MEMORY_BYTES = 8L * 1024 * 1024 * 1024 // 8 GB
    private const val PIDS_LIMIT = 16_384L

    internal const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.gms.debug"

    // The E2E/OAuth broadcast ACTIONS are hardcoded in E2EConfigReceiver/OAuthApprovalTestReceiver using
    // their source package (com.danielealbano.androidremotecontrolmcp.debug) — NOT the applicationId. They
    // must be sent verbatim, independent of the flavor's applicationId suffix (…gms.debug).
    private const val E2E_ACTION_BASE = "com.danielealbano.androidremotecontrolmcp.debug"
    private const val CALCULATOR_PACKAGE = "com.simplemobiletools.calculator"
    private const val COMPOSE_TEST_PACKAGE = "com.danielealbano.composetestapp"
    private const val CALCULATOR_APK_RESOURCE = "/simple-calculator.apk"
    private const val E2E_CONFIG_RECEIVER_CLASS =
        "com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver"
    private const val OAUTH_APPROVAL_RECEIVER_CLASS =
        "com.danielealbano.androidremotecontrolmcp.debug.OAuthApprovalTestReceiver"
    private const val ACCESSIBILITY_SERVICE_CLASS =
        "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
    private const val MAIN_ACTIVITY_CLASS =
        "com.danielealbano.androidremotecontrolmcp.ui.MainActivity"
    internal const val E2E_RECEIVER_COMPONENT = "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS"
    internal const val E2E_CONFIGURE_ACTION = "$E2E_ACTION_BASE.E2E_CONFIGURE"
    internal const val E2E_START_ACTION = "$E2E_ACTION_BASE.E2E_START_SERVER"
    internal const val E2E_STOP_ACTION = "$E2E_ACTION_BASE.E2E_STOP_SERVER"
    internal const val E2E_ARC_ENROLL_ACTION = "$E2E_ACTION_BASE.E2E_ARC_TUNNEL_ENROLL"
    internal const val E2E_ARC_QUERY_ACTION = "$E2E_ACTION_BASE.E2E_ARC_TUNNEL_QUERY"
    internal const val MAIN_COMPONENT = "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS"
    private const val ARC_POLL_INTERVAL_MS = 1_000L
    private const val BROADCAST_DATA_PREFIX = "data=\""

    private const val DEFAULT_EMULATOR_BOOT_TIMEOUT_MS = 180_000L
    private const val DEFAULT_SERVER_READY_TIMEOUT_MS = 60_000L
    private const val POLL_INTERVAL_MS = 2_000L
    private const val SERVER_READY_POLL_INTERVAL_MS = 1_000L

    /**
     * Default bearer token for E2E tests.
     * The app auto-generates a token on first launch. For E2E testing, we set a known
     * token via adb shell command after app installation.
     */
    const val E2E_BEARER_TOKEN = "e2e-test-token-12345"

    /**
     * MCP tool name prefix used by E2E tests.
     *
     * The MCP server builds tool name prefixes via `McpToolUtils.buildToolNamePrefix(deviceSlug)`.
     * With an empty device slug (the default — E2E tests do not configure a device slug in
     * [configureServerSettings]), the prefix is `"android_"`. For example, the base tool name
     * `"press_home"` is registered as `"android_press_home"`.
     *
     * **IMPORTANT**: If the default `device_slug` in `ServerConfig` ever changes, or if
     * [configureServerSettings] starts configuring one, this constant MUST be updated to
     * match. Otherwise all E2E tool calls will fail with "Tool not found" errors.
     */
    const val TOOL_NAME_PREFIX = "android_"

    @Volatile
    private var _adbSerial: String? = null

    val adbSerial: String
        get() = _adbSerial ?: error("Container not booted yet — call container.start() first")

    /**
     * Create a configured redroid container.
     *
     * The container runs native Android via kernel modules (binder_linux, fuse) with:
     * - adb accessible on port 5555
     * - MCP server port directly exposed on container network
     *
     * No nested emulator — ADB connects from host via `adb connect` to the
     * container's mapped port.
     *
     * @return configured [GenericContainer] (not yet started)
     */
    fun createContainer(): GenericContainer<*> {
        println("[E2E Setup] Creating redroid container ($DOCKER_IMAGE)")

        ensureKernelModules()

        val binderDevMajor = detectBinderDevMajor()
        val fuseDevMajorMinor = detectFuseDevMajorMinor()

        return GenericContainer(DockerImageName.parse(DOCKER_IMAGE))
            .withExposedPorts(ADB_PORT, MCP_DEFAULT_PORT)
            .withCommand(
                "androidboot.redroid_width=1080",
                "androidboot.redroid_height=2400",
                "androidboot.redroid_gpu_mode=guest",
                "androidboot.redroid_dpi=420",
                "androidboot.use_memfd=true",
                "ro.product.model=Pixel_6",
                "ro.product.brand=google",
                "ro.product.manufacturer=Google",
                "ro.debuggable=1",
                "ro.secure=0",
            )
            .waitingFor(
                object : AbstractWaitStrategy() {
                    override fun waitUntilReady() {
                        // Redroid writes to Android logcat, not stdout/stderr — container
                        // log-based strategies (LogMessageWaitStrategy) cannot work.
                        // Instead, connect via host-side ADB and poll sys.boot_completed.
                        val host = waitStrategyTarget.host
                        val adbPort = waitStrategyTarget.getMappedPort(ADB_PORT)
                        val serial = "$host:$adbPort"
                        val timeoutMs = startupTimeout.toMillis()

                        println("[E2E Setup] Waiting for redroid boot via ADB $serial (timeout: ${timeoutMs}ms)...")
                        val startTime = System.currentTimeMillis()

                        // Phase 1: wait for adb connect to succeed
                        while (System.currentTimeMillis() - startTime < timeoutMs) {
                            try {
                                val output = runProcess("adb", "connect", serial, timeoutSeconds = 10L)
                                if (output.contains("connected")) {
                                    println("[E2E Setup] ADB connected to $serial")
                                    break
                                }
                                println("[E2E Setup] ADB connect output: $output")
                            } catch (e: Exception) {
                                println("[E2E Setup] ADB connect failed: ${e::class.simpleName}: ${e.message}")
                            }
                            Thread.sleep(POLL_INTERVAL_MS)
                        }

                        // Phase 2: poll sys.boot_completed
                        while (System.currentTimeMillis() - startTime < timeoutMs) {
                            try {
                                val output = runProcess(
                                    "adb", "-s", serial, "shell", "getprop", "sys.boot_completed",
                                    timeoutSeconds = 10L,
                                )
                                if (output == "1") {
                                    val elapsed = System.currentTimeMillis() - startTime
                                    println("[E2E Setup] Redroid boot completed (${elapsed}ms)")
                                    // sys.boot_completed can fire before core system services register with
                                    // the service manager. Enabling the accessibility service and granting
                                    // permissions then race a half-booted framework ("Can't find service:
                                    // settings"). Gate on the services the setup actually uses.
                                    waitForSystemServices(serial, listOf("settings", "package"), timeoutMs - elapsed)
                                    _adbSerial = serial
                                    return
                                }
                                println("[E2E Setup] boot_completed=$output")
                            } catch (e: Exception) {
                                println("[E2E Setup] getprop failed: ${e::class.simpleName}: ${e.message}")
                            }
                            Thread.sleep(POLL_INTERVAL_MS)
                        }

                        throw IllegalStateException(
                            "Redroid did not boot within ${timeoutMs}ms. ADB serial: $serial",
                        )
                    }
                },
            )
            .withStartupTimeout(Duration.ofSeconds(DEFAULT_EMULATOR_BOOT_TIMEOUT_MS / 1000))
            .withCreateContainerCmdModifier { cmd ->
                cmd.withVolumes(Volume("/sys/fs/cgroup"))
                cmd.hostConfig
                    ?.withMemory(MEMORY_BYTES)
                    ?.withMemorySwap(MEMORY_BYTES)
                    // Explicit pids limit so local (podman, default 2048) and CI (docker, default
                    // unlimited) behave identically. A full Android system (system_server, zygote,
                    // HALs, several test apps) legitimately needs thousands of tasks; 2048 is too
                    // tight and container-wide exhaustion kills system_server alongside the app.
                    ?.withPidsLimit(PIDS_LIMIT)
                    ?.withCapAdd(*Capability.values())
                    ?.withSecurityOpts(
                        listOf(
                            "seccomp=unconfined",
                            "apparmor=unconfined",
                        )
                    )
                    ?.withDevices(
                        listOfNotNull(
                            Device("rwm", "/dev/fuse", "/dev/fuse"),
                            if (java.io.File("/dev/dma_heap/system").exists())
                                Device("rwm", "/dev/dma_heap/system", "/dev/dma_heap/system")
                            else null,
                            if (java.io.File("/dev/hwrng").exists())
                                Device("rwm", "/dev/hw_random", "/dev/hwrng")
                            else null,
                        )
                    )
                    ?.withDeviceCgroupRules(
                        listOf(
                            "c $binderDevMajor:* rwm",
                            "c $fuseDevMajorMinor rwm",
                        )
                    )
                    ?.withLogConfig(LogConfig(LogConfig.LoggingType.JSON_FILE))
            }
    }

    /**
     * Install the APK on the redroid container via host-side adb.
     *
     * @param apkPath path to the APK file on the host machine
     * @throws IllegalStateException if APK installation fails
     */
    fun installApk(apkPath: String) {
        val apkFile = File(apkPath)
        require(apkFile.exists()) { "APK file not found: $apkPath" }
        println("[E2E Setup] Installing APK: $apkPath")
        val result = execAdb("install", "-r", apkPath)
        if (!result.contains("Success")) {
            throw IllegalStateException("APK installation failed: $result")
        }
        println("[E2E Setup] APK installed successfully")
    }

    /**
     * Install the Simple Calculator APK for E2E testing.
     *
     * The calculator APK is bundled as a test resource and is used for
     * interaction tests (e.g., calculate 7 + 3 = 10).
     */
    fun installCalculatorApk() {
        println("[E2E Setup] Installing calculator APK for testing...")
        val resourceStream = AndroidContainerSetup::class.java.getResourceAsStream(CALCULATOR_APK_RESOURCE)
            ?: throw IllegalStateException("Calculator APK not found in test resources: $CALCULATOR_APK_RESOURCE")
        val tempFile = File.createTempFile("calculator", ".apk")
        tempFile.deleteOnExit()
        resourceStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
        val result = execAdb("install", "-r", tempFile.absolutePath)
        if (!result.contains("Success")) {
            throw IllegalStateException("Calculator APK installation failed: $result")
        }
        println("[E2E Setup] Calculator APK installed successfully")
    }

    /**
     * Grant camera and microphone runtime permissions via adb.
     *
     * These permissions are required for the camera MCP tools (take_camera_photo,
     * save_camera_photo, save_camera_video). Granting via `pm grant` avoids the
     * need for interactive permission dialogs in E2E tests.
     *
     * Must be called after APK installation.
     */
    fun grantCameraPermissions() {
        println("[E2E Setup] Granting camera and microphone permissions...")
        execAdb("shell", "pm", "grant", APP_PACKAGE, "android.permission.CAMERA")
        execAdb("shell", "pm", "grant", APP_PACKAGE, "android.permission.RECORD_AUDIO")
        println("[E2E Setup] Camera and microphone permissions granted")
    }

    /**
     * Enable the accessibility service via adb shell settings command.
     *
     * This sets the accessibility service as enabled in the secure settings,
     * which is equivalent to the user toggling it on in Settings > Accessibility.
     *
     * IMPORTANT: This must be called AFTER the app process is running (i.e., after
     * startMcpServer), because force-stop in configureServerSettings kills the
     * process and disconnects any previously-bound accessibility service. Enabling
     * after the app is running ensures the system can immediately bind.
     *
     * After writing the settings, this method polls `dumpsys accessibility` to verify
     * the service is actually connected before returning.
     *
     * @param timeoutMs maximum time to wait for the service to connect (default 30 seconds)
     * @throws IllegalStateException if the service does not connect within timeout
     */
    fun enableAccessibilityService(timeoutMs: Long = 30_000L) {
        println("[E2E Setup] Enabling accessibility service...")
        val serviceComponent = "$APP_PACKAGE/$ACCESSIBILITY_SERVICE_CLASS"

        execAdb("shell", "settings", "put", "secure", "enabled_accessibility_services", serviceComponent)
        execAdb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

        println("[E2E Setup] Waiting for accessibility service to connect (timeout: ${timeoutMs}ms)...")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val dumpsys = execAdb("shell", "dumpsys", "accessibility")
                if (dumpsys.contains("McpAccessibilityService") && dumpsys.contains("Service")) {
                    println("[E2E Setup] Accessibility service connected (${System.currentTimeMillis() - startTime}ms)")
                    return
                }
            } catch (_: Exception) { }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        throw IllegalStateException(
            "Accessibility service did not connect within ${timeoutMs}ms. Component: $serviceComponent"
        )
    }

    /**
     * Checks whether the accessibility service is currently connected.
     *
     * Runs `dumpsys accessibility` via host-side adb and checks for
     * `McpAccessibilityService` in the output.
     *
     * @return true if the accessibility service appears connected
     */
    fun isAccessibilityServiceConnected(): Boolean =
        try {
            val dumpsys = execAdb("shell", "dumpsys", "accessibility")
            dumpsys.contains("McpAccessibilityService") && dumpsys.contains("Service")
        } catch (_: Exception) {
            false
        }

    /**
     * Ensures the accessibility service is connected, re-enabling it if necessary.
     *
     * This is intended to be called before each test method to recover from
     * transient accessibility service disconnections (e.g., after camera
     * operations or app restarts on slow CI).
     */
    fun ensureAccessibilityService() {
        if (isAccessibilityServiceConnected()) return
        println("[E2E Setup] Accessibility service not connected, re-enabling...")
        enableAccessibilityService()
    }

    /**
     * Configure the MCP server settings for E2E testing.
     *
     * Launches the app activity once to trigger initial DataStore creation,
     * then uses the debug-only BroadcastReceiver to inject test settings
     * (bearer token, binding address, port) via adb broadcast.
     */
    fun configureServerSettings() {
        println("[E2E Setup] Configuring MCP server settings...")

        execAdb("shell", "am", "start", "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS")
        Thread.sleep(5_000)

        execAdb("shell", "am", "force-stop", APP_PACKAGE)
        Thread.sleep(1_000)

        val configAction = "$E2E_ACTION_BASE.E2E_CONFIGURE"
        execAdb(
            "shell", "am", "broadcast",
            "--include-stopped-packages",
            "-a", configAction,
            "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS",
            "--es", "bearer_token", E2E_BEARER_TOKEN,
            "--es", "binding_address", "0.0.0.0",
            "--ei", "port", MCP_DEFAULT_PORT.toString(),
        )
        Thread.sleep(3_000)

        println("[E2E Setup] Server settings configured")
    }

    /** Approves all currently-pending OAuth authorizations via the debug-only approval receiver. */
    fun approvePendingOAuth() {
        val action = "$E2E_ACTION_BASE.OAUTH_APPROVE"
        execAdb(
            "shell", "am", "broadcast",
            "-a", action,
            "-n", "$APP_PACKAGE/$OAUTH_APPROVAL_RECEIVER_CLASS",
        )
        Thread.sleep(1_000)
    }

    /**
     * Start the MCP server by launching the MainActivity and then sending
     * a broadcast to the debug-only E2EConfigReceiver to start the foreground service.
     *
     * The service is `exported=false` in the manifest, so it cannot be started
     * directly via `adb shell am startservice`. Instead, the E2EConfigReceiver
     * runs inside the app process and calls `context.startForegroundService()`.
     */
    fun startMcpServer() {
        println("[E2E Setup] Starting MCP server...")

        execAdb("shell", "am", "start", "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS")
        Thread.sleep(5_000)

        val startServerAction = "$E2E_ACTION_BASE.E2E_START_SERVER"
        execAdb(
            "shell", "am", "broadcast",
            "-a", startServerAction,
            "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS",
        )
        Thread.sleep(5_000)

        println("[E2E Setup] MCP server start commands sent")
    }

    /**
     * Launch the calculator app using am start.
     *
     * Uses `am start` with explicit component name instead of `monkey` because
     * the `monkey` command is unavailable in redroid containers (exit 251).
     */
    fun launchCalculator() {
        val result = execAdb(
            "shell", "am", "start", "-W",
            "-n", "$CALCULATOR_PACKAGE/.activities.MainActivity",
        )
        println("[E2E Setup] launchCalculator result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the compose test app using am start.
     */
    fun launchComposeTestApp() {
        val result = execAdb(
            "shell", "am", "start", "-W",
            "-n", "$COMPOSE_TEST_PACKAGE/.MainActivity",
        )
        println("[E2E Setup] launchComposeTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Send a broadcast to the compose test app to update the displayed number.
     *
     * @param number the number to display
     */
    fun sendComposeTestNumber(number: Int) {
        val result = execAdb(
            "shell", "am", "start",
            "--activity-single-top",
            "-n", "$COMPOSE_TEST_PACKAGE/.MainActivity",
            "--ei", "number", number.toString(),
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
        val result = execAdb(
            "shell", "am", "start", "-W", "-S",
            "-n", "$COMPOSE_TEST_PACKAGE/.LongListActivity",
        )
        println("[E2E Setup] launchLongListTestApp result: $result")
        Thread.sleep(2_000)
    }

    /**
     * Launch the WebView test activity using am start.
     */
    fun launchWebViewTestApp() {
        val result = execAdb(
            "shell", "am", "start", "-W",
            "-n", "$COMPOSE_TEST_PACKAGE/.WebViewActivity",
        )
        println("[E2E Setup] launchWebViewTestApp result: $result")
        Thread.sleep(3_000)
    }

    /**
     * Launch the WebView test activity with the large, content-heavy page
     * (hundreds of articles) used to exercise WebView node collapsing.
     */
    fun launchHeavyWebViewTestApp() {
        val result = execAdb(
            "shell", "am", "start", "-W",
            "--es", "content", "heavy",
            "-n", "$COMPOSE_TEST_PACKAGE/.WebViewActivity",
        )
        println("[E2E Setup] launchHeavyWebViewTestApp result: $result")
        Thread.sleep(3_000)
    }

    /**
     * Counts the accessibility nodes currently on screen via `uiautomator dump`. This is the raw,
     * un-collapsed node count (every node the platform exposes), used as the baseline against the
     * collapsed `get_screen_state` output.
     *
     * @return the number of `<node` elements in the dump, or 0 if the dump could not be produced.
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
     * filtering that `get_screen_state` always applies.
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
        val result = execAdb(
            "shell", "am", "start",
            "--activity-single-top",
            "-n", "$COMPOSE_TEST_PACKAGE/.WebViewActivity",
            "--ei", "number", number.toString(),
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

    /**
     * Poll the MCP endpoint until the server is ready to accept requests.
     *
     * Sends an HTTP POST to `/mcp` with a minimal JSON-RPC body. Any HTTP
     * response (even an error) proves the Ktor server is running and processing.
     *
     * @param baseUrl the base URL of the MCP server (e.g., "http://localhost:8080")
     * @param timeoutMs maximum time to wait for server ready (default 60 seconds)
     * @throws IllegalStateException if server does not become ready within timeout
     */
    fun waitForServerReady(
        baseUrl: String,
        timeoutMs: Long = DEFAULT_SERVER_READY_TIMEOUT_MS,
    ) {
        println("[E2E Setup] Waiting for MCP server to be ready at $baseUrl (timeout: ${timeoutMs}ms)...")

        val startTime = System.currentTimeMillis()
        var lastError: String? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URI("$baseUrl/mcp").toURL()
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $E2E_BEARER_TOKEN")
                conn.doOutput = true
                conn.outputStream.use {
                    it.write("""{"jsonrpc":"2.0","method":"ping","id":1}""".toByteArray())
                }
                val responseCode = conn.responseCode
                if (responseCode > 0) {
                    println(
                        "[E2E Setup] MCP server is ready " +
                            "(HTTP $responseCode, ${System.currentTimeMillis() - startTime}ms)"
                    )
                    return
                }
            } catch (e: Exception) {
                val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
                if (errorMsg != lastError) {
                    println("[E2E Setup] Server readiness poll: $errorMsg")
                    lastError = errorMsg
                }
            } finally {
                conn?.disconnect()
            }
            Thread.sleep(SERVER_READY_POLL_INTERVAL_MS)
        }

        dumpDiagnostics()

        throw IllegalStateException(
            "MCP server did not become ready within ${timeoutMs}ms at $baseUrl"
        )
    }

    /**
     * Get the mapped MCP server URL from a running container.
     *
     * @param container the running redroid container
     * @return the base URL for the MCP server (e.g., "http://localhost:32768")
     */
    fun getMcpServerUrl(container: GenericContainer<*>): String {
        val host = container.host
        val port = container.getMappedPort(MCP_DEFAULT_PORT)
        return "http://$host:$port"
    }

    /**
     * Disconnect ADB from the redroid container.
     * Best-effort cleanup — errors are silently ignored.
     */
    fun disconnectAdb() {
        val serial = _adbSerial ?: return
        try {
            runProcess("adb", "disconnect", serial, timeoutSeconds = 10L)
            println("[E2E Setup] Disconnected adb from $serial")
        } catch (_: Exception) {
            // Best-effort cleanup
        }
        _adbSerial = null
    }

    /**
     * Ensures binder and fuse are available to redroid.
     *
     * On CI, modules are pre-loaded and binderfs mounted by the workflow. When [kernelPrerequisitesSatisfied] reports
     * them usable (binderfs mounted, fuse loaded or built in) nothing is done; otherwise attempts `sudo modprobe`
     * plus the binderfs mount and provides a clear error on failure.
     *
     * NOTE: Uses `sudo` — accepted exception to CLAUDE.md no-sudo rule.
     * Kernel module loading is a host-OS operation required by redroid.
     */
    private fun ensureKernelModules() {
        val satisfied =
            kernelPrerequisitesSatisfied(
                procModules = readProcModules(),
                devFuseExists = File("/dev/fuse").exists(),
                binderfsMounted = isBinderfsMounted(),
            )
        if (satisfied) {
            println("[E2E Setup] Kernel prerequisites already available (binder, fuse)")
            return
        }

        println("[E2E Setup] Loading kernel modules via sudo modprobe...")
        try {
            runProcess("sudo", "modprobe", "binder_linux", "devices=binder,hwbinder,vndbinder",
                timeoutSeconds = 60L)
            runProcess("sudo", "modprobe", "fuse", timeoutSeconds = 60L)
            ensureBinderfs()
            println("[E2E Setup] Kernel modules loaded successfully")
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to load kernel modules required by redroid. " +
                    "On Linux, ensure 'binder_linux' and 'fuse' modules are available. " +
                    "Install with: sudo apt-get install linux-modules-extra-\$(uname -r) && " +
                    "sudo modprobe binder_linux devices=\"binder,hwbinder,vndbinder\" && " +
                    "sudo modprobe fuse. Error: ${e.message}",
                e,
            )
        }
    }

    /**
     * Mounts binderfs at /dev/binderfs/ if not already mounted.
     * NOTE: Uses `sudo` — same accepted exception as ensureKernelModules.
     */
    private fun ensureBinderfs() {
        if (isBinderfsMounted()) return

        println("[E2E Setup] Mounting binderfs at /dev/binderfs/...")
        runProcess("sudo", "mkdir", "-p", "/dev/binderfs")
        runProcess("sudo", "mount", "-t", "binder", "binder", "/dev/binderfs")
    }

    /**
     * True when binder and fuse are already usable, so the sudo `modprobe` path can be skipped. Binder counts only
     * when `/dev/binderfs` is mounted, module or built in: [detectBinderDevMajor] reads the device major from that
     * mount, so a loaded `binder_linux` without it still needs [ensureBinderfs]. Built-in (non-module) fuse never
     * appears in [procModules], so an existing `/dev/fuse` also counts.
     */
    internal fun kernelPrerequisitesSatisfied(
        procModules: String,
        devFuseExists: Boolean,
        binderfsMounted: Boolean,
    ): Boolean = binderfsMounted && (devFuseExists || procModules.contains("fuse"))

    private fun readProcModules(): String =
        try {
            File("/proc/modules").readText()
        } catch (_: IOException) {
            ""
        }

    private fun isBinderfsMounted(): Boolean =
        try {
            runProcess("mountpoint", "-q", "/dev/binderfs")
            true
        } catch (_: IllegalStateException) {
            // Non-zero exit (not a mount point) or timeout.
            false
        } catch (_: IOException) {
            // `mountpoint` could not be started.
            false
        }

    private fun detectBinderDevMajor(): String =
        try {
            val output = runProcess("stat", "-c", "%Hr", "/dev/binderfs/binder-control")
            if (output.isNotEmpty()) {
                println("[E2E Setup] Binder device major: $output")
                output
            } else {
                println("[E2E Setup] Could not detect binder device major, using default 10")
                "10"
            }
        } catch (e: Exception) {
            println("[E2E Setup] Binder device major detection failed: ${e.message}, using default 10")
            "10"
        }

    private fun detectFuseDevMajorMinor(): String =
        try {
            val content = File("/sys/class/misc/fuse/dev").readText().trim()
            if (content.isNotEmpty()) {
                println("[E2E Setup] Fuse device major:minor: $content")
                content
            } else {
                println("[E2E Setup] Could not detect fuse device, using default 10:229")
                "10:229"
            }
        } catch (e: Exception) {
            println("[E2E Setup] Fuse device detection failed: ${e.message}, using default 10:229")
            "10:229"
        }

    /**
     * The address of the HOST as seen from inside the container: its default-gateway IP (podman's bridge).
     * `host.testcontainers.internal` is not resolvable by Android's resolver; Android uses policy routing, so
     * `ip route get <external ip>` is used instead of the main table (prints "<ip> via <gateway> dev …").
     */
    fun containerGatewayIp(serial: String = adbSerial): String {
        val out = execAdbOn(serial, "shell", "ip", "route", "get", "1.1.1.1")
        val tokens = out.split(Regex("\\s+"))
        return tokens[tokens.indexOf("via") + 1]
    }

    /**
     * Stops the MCP server through the debug receiver and waits until nothing answers on [baseUrl]. The main
     * activity is brought to the front first: the stop is a foreground-service command, refused from the
     * background on Android 12+.
     */
    fun stopMcpServer(
        baseUrl: String,
        serial: String = adbSerial,
        timeoutMs: Long = DEFAULT_SERVER_READY_TIMEOUT_MS,
    ) {
        println("[E2E Setup] Stopping MCP server...")
        execAdbOn(serial, "shell", "am", "start", "-W", "-n", MAIN_COMPONENT)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_STOP_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URI("$baseUrl/health").toURL().openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                conn.responseCode
            } catch (_: java.io.IOException) {
                println("[E2E Setup] MCP server stopped (${System.currentTimeMillis() - startTime}ms)")
                return
            } finally {
                conn?.disconnect()
            }
            Thread.sleep(SERVER_READY_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("MCP server still answering ${timeoutMs}ms after the stop broadcast")
    }

    /** Stops the server on a device without a reachable base URL; awaits `server == "Stopped"` through the query. */
    fun stopMcpServerOn(serial: String) {
        execAdbOn(serial, "shell", "am", "start", "-W", "-n", MAIN_COMPONENT)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_STOP_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        awaitArcTunnel(serial = serial, timeoutMs = 30_000L) { it.server == "Stopped" }
    }

    /** Starts the server on [serial] (activity + debug receiver), like [startMcpServer] but for any device. */
    fun startMcpServerOn(serial: String) {
        execAdbOn(serial, "shell", "am", "start", "-W", "-n", MAIN_COMPONENT)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_START_ACTION, "-n", E2E_RECEIVER_COMPONENT)
    }

    /**
     * Applies the free E2E tunnel settings through the debug receiver and waits until the query echoes them.
     * Empty override/PEM are sent as `-` (the receiver's clear sentinel: `--es k ""` never arrives).
     */
    fun configureArcTunnel(
        settings: ArcTunnelE2ESettings,
        serial: String = adbSerial,
    ) {
        val pemB64 =
            if (settings.trustedCaPem.isEmpty()) {
                "-"
            } else {
                Base64.getEncoder().encodeToString(settings.trustedCaPem.toByteArray())
            }
        execAdbOn(
            serial, "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", E2E_CONFIGURE_ACTION, "-n", E2E_RECEIVER_COMPONENT,
            "--ez", "tunnel_enabled", settings.tunnelEnabled.toString(),
            "--es", "tunnel_provider", settings.provider,
            "--es", "arc_tunnel_base_address", settings.baseAddress,
            "--ez", "arc_tunnel_ct_check_enabled", settings.ctCheckEnabled.toString(),
            "--es", "arc_tunnel_edge_override", settings.edgeOverride.ifEmpty { "-" },
            "--es", "arc_tunnel_trusted_ca_pem_b64", pemB64,
        )
        awaitArcTunnel(serial = serial, timeoutMs = 30_000L) { status ->
            status.tunnelEnabled == settings.tunnelEnabled &&
                status.tunnelProvider == settings.provider &&
                status.baseAddress == settings.baseAddress &&
                status.ctCheckEnabled == settings.ctCheckEnabled &&
                status.edgeOverride == settings.edgeOverride
        }
        println("[E2E Setup] Free E2E tunnel settings applied")
    }

    /** Brings the app to the foreground first: the enrollment foreground service cannot start from the background. */
    fun enrollArcTunnel(serial: String = adbSerial) {
        execAdbOn(serial, "shell", "am", "start", "-W", "-n", MAIN_COMPONENT)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_ARC_ENROLL_ACTION, "-n", E2E_RECEIVER_COMPONENT)
    }

    /** One status query; the receiver answers with Base64 JSON in the broadcast result data. */
    fun queryArcTunnel(serial: String = adbSerial): E2EArcTunnelStatus {
        val out =
            execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_ARC_QUERY_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        val line =
            out.lines().firstOrNull { it.contains(BROADCAST_DATA_PREFIX) }
                ?: throw IllegalStateException("No result data in: $out")
        val b64 = line.substringAfter(BROADCAST_DATA_PREFIX).substringBefore('"')
        return E2EArcTunnelStatus.parse(String(Base64.getDecoder().decode(b64)))
    }

    /** Polls [queryArcTunnel] until [predicate] holds; the last status is in the failure message. */
    fun awaitArcTunnel(
        serial: String = adbSerial,
        timeoutMs: Long,
        predicate: (E2EArcTunnelStatus) -> Boolean,
    ): E2EArcTunnelStatus {
        val startTime = System.currentTimeMillis()
        var last: E2EArcTunnelStatus? = null
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            last = queryArcTunnel(serial)
            if (predicate(last)) return last
            Thread.sleep(ARC_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Free E2E tunnel condition not met within ${timeoutMs}ms; last status: $last")
    }

    /** [execAdb] for an explicit serial (the device-gated test targets the attached phone, not the container). */
    internal fun execAdbOn(
        serial: String,
        vararg args: String,
    ): String = runProcess("adb", "-s", serial, *args)

    /**
     * Execute an adb command on the host targeting the redroid container.
     * Returns stdout on success. Uses [PROCESS_TIMEOUT_SECONDS] timeout.
     */
    internal fun execAdb(vararg args: String): String {
        val command = arrayOf("adb", "-s", adbSerial) + args
        return runProcess(*command)
    }

    /**
     * Polls `service check <name>` until each named system service is registered with the
     * service manager, or the timeout elapses. `sys.boot_completed` fires before services like
     * `settings` and `package` are up, so subsequent `settings put` / `pm grant` steps otherwise
     * race a half-booted framework and fail with "Can't find service: <name>".
     *
     * @throws IllegalStateException if any service is not registered within [timeoutMs]
     */
    private fun waitForSystemServices(serial: String, services: List<String>, timeoutMs: Long) {
        for (service in services) {
            println("[E2E Setup] Waiting for system service '$service'...")
            val startTime = System.currentTimeMillis()
            var ready = false
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val output = runProcess(
                        "adb", "-s", serial, "shell", "service", "check", service,
                        timeoutSeconds = 10L,
                    )
                    // `service check` prints "Service <name>: found" once registered.
                    if (output.contains("found", ignoreCase = true) &&
                        !output.contains("not found", ignoreCase = true)
                    ) {
                        println("[E2E Setup] Service '$service' ready (${System.currentTimeMillis() - startTime}ms)")
                        ready = true
                        break
                    }
                } catch (e: Exception) {
                    println("[E2E Setup] service check '$service' failed: ${e::class.simpleName}: ${e.message}")
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            if (!ready) {
                throw IllegalStateException(
                    "System service '$service' did not register within ${timeoutMs}ms. ADB serial: $serial",
                )
            }
        }
    }

    /**
     * Dump diagnostic information to help debug server readiness issues.
     * Only called when the health check times out.
     */
    private fun dumpDiagnostics() {
        System.err.println("[E2E Diagnostics] === Server readiness timeout — dumping diagnostics ===")

        try {
            val ss = execAdb("shell", "ss", "-tlnp")
            System.err.println("[E2E Diagnostics] LISTEN ports: $ss")
        } catch (e: Exception) {
            System.err.println("[E2E Diagnostics] ss failed: ${e.message}")
        }

        try {
            val logcat = execAdb("shell", "logcat", "-d", "-t", "50")
            val filtered = logcat.lines()
                .filter {
                    it.contains("MCP", ignoreCase = true) ||
                        it.contains("E2E", ignoreCase = true) ||
                        it.contains("FATAL", ignoreCase = true)
                }
                .takeLast(20)
                .joinToString("\n")
            System.err.println("[E2E Diagnostics] logcat (MCP/E2E): $filtered")
        } catch (e: Exception) {
            System.err.println("[E2E Diagnostics] logcat failed: ${e.message}")
        }

        System.err.println("[E2E Diagnostics] === End diagnostics dump ===")
    }

    /**
     * Runs a process with a timeout. Returns trimmed stdout on success.
     * Destroys the process forcibly on timeout.
     *
     * Stdout is read in a separate thread to avoid deadlock: if the process
     * hangs without closing stdout (e.g., `sudo` waiting for password),
     * `readText()` would block forever, preventing `waitFor()` from firing.
     * By reading in a thread, `waitFor()` can time out and `destroyForcibly()`.
     */
    internal fun runProcess(
        vararg command: String,
        timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
    ): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val outputBuilder = StringBuilder()
        val readerThread = Thread {
            try {
                outputBuilder.append(process.inputStream.bufferedReader().readText())
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            readerThread.join(1_000)
            throw IllegalStateException(
                "Process timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}"
            )
        }
        readerThread.join(5_000)
        val output = outputBuilder.toString().trim()

        if (process.exitValue() != 0) {
            throw IllegalStateException(
                "Process '${command.joinToString(" ")}' failed (exit ${process.exitValue()}): $output"
            )
        }
        return output
    }
}
