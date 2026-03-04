package com.danielealbano.androidremotecontrolmcp.e2e

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.Device
import com.github.dockerjava.api.model.Volume
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Manages the redroid Docker container lifecycle for E2E tests.
 *
 * Responsibilities:
 * - Create and configure the redroid container with kernel module setup
 * - Wait for boot completion via host-side ADB connection
 * - Install APK and configure permissions via host-side adb commands
 * - Start the MCP server and wait for it to be ready
 */
object AndroidContainerSetup {

    private const val DOCKER_IMAGE = "redroid/redroid:13.0.0-latest"
    private const val ADB_PORT = 5555
    private const val MCP_DEFAULT_PORT = 8080
    private const val PROCESS_TIMEOUT_SECONDS = 30L
    private const val MEMORY_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB

    private const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.debug"
    private const val CALCULATOR_PACKAGE = "com.simplemobiletools.calculator"
    private const val CALCULATOR_APK_RESOURCE = "/simple-calculator.apk"
    private const val E2E_CONFIG_RECEIVER_CLASS =
        "com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver"
    private const val ACCESSIBILITY_SERVICE_CLASS =
        "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
    private const val MAIN_ACTIVITY_CLASS =
        "com.danielealbano.androidremotecontrolmcp.ui.MainActivity"

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
        get() = _adbSerial ?: error("Container not booted yet — call waitForEmulatorBoot first")

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
            .withStartupTimeout(Duration.ofSeconds(120))
            .waitingFor(
                Wait.forLogMessage(".*sys\\.boot_completed=1.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(120))
            )
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig
                    ?.withMemory(MEMORY_BYTES)
                    ?.withMemorySwap(MEMORY_BYTES)
                    ?.withCapAdd(Capability.values().toList())
                    ?.withSecurityOpts(
                        listOf(
                            "seccomp=unconfined",
                            "apparmor=unconfined",
                        )
                    )
                    ?.withDevices(
                        listOf(
                            Device("rwm", "/dev/fuse", "/dev/fuse"),
                        )
                    )
                    ?.withDeviceCgroupRules(
                        listOf(
                            "c $binderDevMajor:* rwm",
                            "c $fuseDevMajorMinor rwm",
                        )
                    )
                    ?.withBinds(
                        Bind("/sys/fs/cgroup", Volume("/sys/fs/cgroup")),
                    )
            }
    }

    /**
     * Wait for the redroid container to boot and establish host-side ADB connection.
     *
     * Redroid has no adb binary inside the container. We connect from the host
     * via `adb connect` to the container's mapped ADB port, then poll
     * `getprop sys.boot_completed`.
     */
    fun waitForEmulatorBoot(
        container: GenericContainer<*>,
        timeoutMs: Long = DEFAULT_EMULATOR_BOOT_TIMEOUT_MS,
    ) {
        val host = container.host
        val adbPort = container.getMappedPort(ADB_PORT)
        val serial = "$host:$adbPort"

        println("[E2E Setup] Waiting for redroid boot via adb connect $serial (timeout: ${timeoutMs}ms)...")
        val startTime = System.currentTimeMillis()

        // Phase 1: wait for adb connect to succeed
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val output = runProcess("adb", "connect", serial, timeoutSeconds = 10L)
                if (output.contains("connected")) break
            } catch (_: Exception) { }
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
                    println("[E2E Setup] Redroid boot completed (${System.currentTimeMillis() - startTime}ms)")
                    _adbSerial = serial
                    return
                }
            } catch (_: Exception) { }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        throw IllegalStateException("Redroid did not boot within ${timeoutMs}ms. ADB serial: $serial")
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

        val configAction = "$APP_PACKAGE.E2E_CONFIGURE"
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

        val startServerAction = "$APP_PACKAGE.E2E_START_SERVER"
        execAdb(
            "shell", "am", "broadcast",
            "-a", startServerAction,
            "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS",
        )
        Thread.sleep(5_000)

        println("[E2E Setup] MCP server start commands sent")
    }

    /**
     * Launch the calculator app using monkey command.
     */
    fun launchCalculator() {
        execAdb("shell", "monkey", "-p", CALCULATOR_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
        Thread.sleep(2_000)
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
     * @param container the running Docker container
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
     * Ensures binder_linux and fuse kernel modules are loaded.
     *
     * On CI, modules are pre-loaded by the workflow. On local dev, attempts
     * `sudo modprobe` and provides a clear error on failure.
     *
     * NOTE: Uses `sudo` — accepted exception to CLAUDE.md no-sudo rule.
     * Kernel module loading is a host-OS operation required by redroid.
     */
    private fun ensureKernelModules() {
        if (isModuleLoaded("binder_linux") && isModuleLoaded("fuse")) {
            println("[E2E Setup] Kernel modules already loaded")
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
        try {
            runProcess("mountpoint", "-q", "/dev/binderfs")
            return // Already mounted
        } catch (_: Exception) {
            // Not mounted, proceed
        }

        println("[E2E Setup] Mounting binderfs at /dev/binderfs/...")
        runProcess("sudo", "mkdir", "-p", "/dev/binderfs")
        runProcess("sudo", "mount", "-t", "binder", "binder", "/dev/binderfs")
    }

    private fun isModuleLoaded(moduleName: String): Boolean =
        try {
            File("/proc/modules").readText().contains(moduleName)
        } catch (_: Exception) {
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
     * Execute an adb command on the host targeting the redroid container.
     * Returns stdout on success. Uses [PROCESS_TIMEOUT_SECONDS] timeout.
     */
    private fun execAdb(vararg args: String): String {
        val command = arrayOf("adb", "-s", adbSerial) + args
        return runProcess(*command)
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
    private fun runProcess(
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
