package com.danielealbano.androidremotecontrolmcp.e2e

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.Duration

/**
 * Manages the Docker Android container lifecycle for E2E tests.
 *
 * Responsibilities:
 * - Create and configure the Docker Android container
 * - Wait for emulator boot completion
 * - Install APK and configure permissions
 * - Start the MCP server and wait for it to be ready
 */
object AndroidContainerSetup {

    private const val DOCKER_IMAGE = "budtmo/docker-android:emulator_14.0"
    private const val ADB_PORT = 5555
    private const val NOVNC_PORT = 6080
    private const val MCP_DEFAULT_PORT = 8080
    private const val ADB_FORWARD_PORT = 18080

    private const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.debug"
    private const val E2E_CONFIG_RECEIVER_CLASS =
        "com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver"
    private const val ACCESSIBILITY_SERVICE_CLASS =
        "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
    private const val MAIN_ACTIVITY_CLASS =
        "com.danielealbano.androidremotecontrolmcp.ui.MainActivity"

    private const val DEFAULT_EMULATOR_BOOT_TIMEOUT_MS = 180_000L
    private const val DEFAULT_SERVER_READY_TIMEOUT_MS = 60_000L
    private const val POLL_INTERVAL_MS = 2_000L
    private const val HEALTH_POLL_INTERVAL_MS = 1_000L

    /**
     * Default bearer token for E2E tests.
     * The app auto-generates a token on first launch. For E2E testing, we set a known
     * token via adb shell command after app installation.
     */
    const val E2E_BEARER_TOKEN = "e2e-test-token-12345"

    /**
     * Create a configured Docker Android container.
     *
     * The container runs a full Android emulator (API 34, x86) with:
     * - adb accessible on port 5555
     * - noVNC accessible on port 6080 (for visual debugging)
     * - MCP server port exposed and forwarded from the emulator to the container
     *
     * Port forwarding chain:
     *   Test host -> (Testcontainers mapped port) -> Docker container:MCP_DEFAULT_PORT
     *     -> (adb forward inside container) -> Android emulator:MCP_DEFAULT_PORT
     *
     * @return configured [GenericContainer] (not yet started)
     */
    fun createContainer(): GenericContainer<*> {
        println("[E2E Setup] Creating Docker Android container ($DOCKER_IMAGE)")

        val isCI = System.getenv("CI") == "true"
        val emulatorArgs = buildString {
            append("-no-boot-anim -no-audio -no-snapshot")
            if (isCI) append(" -no-window")
        }
        val memoryBytes = if (isCI) 5632L * 1024 * 1024 else 6L * 1024 * 1024 * 1024 // 5.5 GB CI, 6 GB local

        return GenericContainer(DockerImageName.parse(DOCKER_IMAGE))
            .withExposedPorts(ADB_PORT, NOVNC_PORT, MCP_DEFAULT_PORT)
            .withEnv("EMULATOR_DEVICE", "Nexus 5")
            .withEnv("WEB_VNC", if (isCI) "false" else "true")
            .withEnv("EMULATOR_HEADLESS", if (isCI) "true" else "false")
            .withEnv("EMULATOR_ADDITIONAL_ARGS", emulatorArgs)
            .withEnv("USER_BEHAVIOR_ANALYTICS", "false")
            .withEnv("EMULATOR_DATA_PARTITION", "2048m")
            .withPrivilegedMode(true)
            .withStartupTimeout(Duration.ofSeconds(300))
            .waitingFor(
                Wait.forLogMessage(".*device entered RUNNING state.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(300))
            )
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig?.withMemory(memoryBytes)
                cmd.hostConfig?.withMemorySwap(memoryBytes)
            }
    }

    /**
     * Set up port forwarding inside the Docker container.
     *
     * The MCP server runs inside the Android emulator, which has its own
     * network stack. Testcontainers maps the Docker container's port to the
     * test host, but we need to bridge from the container's `0.0.0.0:8080`
     * (which Docker port mapping connects to) to the emulator's port.
     *
     * `adb forward` only binds to `127.0.0.1`, which Docker port mapping
     * cannot reach. Instead, we use `socat` to listen on all interfaces and
     * forward to the emulator via the adb forward.
     *
     * Forward chain:
     *   test host:randomPort <-> container:0.0.0.0:MCP_DEFAULT_PORT <-> (socat)
     *     <-> container:127.0.0.1:adb_forward_port <-> emulator:MCP_DEFAULT_PORT
     *
     * @param container the running Docker container
     */
    fun setupPortForwarding(container: GenericContainer<*>) {
        println("[E2E Setup] Setting up port forwarding (container:$MCP_DEFAULT_PORT -> emulator:$MCP_DEFAULT_PORT)...")

        // Step 1: Install socat (not included in the Docker image by default).
        container.execInContainer(
            "sh", "-c",
            "apt-get update -qq && apt-get install -y -qq socat > /dev/null 2>&1"
        )

        // Step 2: Set up adb forward from container's localhost to the emulator.
        // This binds to 127.0.0.1:ADB_FORWARD_PORT inside the container.
        execInContainer(
            container,
            "adb", "forward", "tcp:$ADB_FORWARD_PORT", "tcp:$MCP_DEFAULT_PORT"
        )

        // Step 3: Use socat to bridge from 0.0.0.0:MCP_DEFAULT_PORT (Docker port mapping)
        // to 127.0.0.1:ADB_FORWARD_PORT (adb forward). Run in background.
        container.execInContainer(
            "sh", "-c",
            "nohup socat TCP-LISTEN:$MCP_DEFAULT_PORT,fork,reuseaddr,bind=0.0.0.0 TCP:127.0.0.1:$ADB_FORWARD_PORT &"
        )
        Thread.sleep(1_000)

        println("[E2E Setup] Port forwarding established")
    }

    /**
     * Wait for the Android emulator inside the container to fully boot.
     *
     * Polls `adb shell getprop sys.boot_completed` until it returns "1"
     * or the timeout is exceeded.
     *
     * @param container the running Docker container
     * @param timeoutMs maximum time to wait for boot (default 180 seconds)
     * @throws IllegalStateException if emulator does not boot within timeout
     */
    fun waitForEmulatorBoot(
        container: GenericContainer<*>,
        timeoutMs: Long = DEFAULT_EMULATOR_BOOT_TIMEOUT_MS,
    ) {
        println("[E2E Setup] Waiting for emulator boot (timeout: ${timeoutMs}ms)...")

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val result = execInContainer(container, "adb", "shell", "getprop", "sys.boot_completed")
                if (result.trim() == "1") {
                    println("[E2E Setup] Emulator boot completed (${System.currentTimeMillis() - startTime}ms)")
                    return
                }
            } catch (_: Exception) {
                // adb may not be ready yet, continue polling
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        throw IllegalStateException(
            "Emulator did not boot within ${timeoutMs}ms. Check container logs for details."
        )
    }

    /**
     * Install the APK on the emulator inside the container.
     *
     * Copies the APK file into the container and installs it via adb.
     *
     * @param container the running Docker container
     * @param apkPath path to the APK file on the host machine
     * @throws IllegalStateException if APK installation fails
     */
    fun installApk(container: GenericContainer<*>, apkPath: String) {
        val apkFile = File(apkPath)
        require(apkFile.exists()) { "APK file not found: $apkPath" }

        println("[E2E Setup] Installing APK: $apkPath")

        // Copy APK into container
        container.copyFileToContainer(
            org.testcontainers.utility.MountableFile.forHostPath(apkPath),
            "/tmp/app.apk"
        )

        // Install via adb
        val result = execInContainer(container, "adb", "install", "-r", "/tmp/app.apk")
        if (!result.contains("Success")) {
            throw IllegalStateException("APK installation failed: $result")
        }

        println("[E2E Setup] APK installed successfully")
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
     * @param container the running Docker container
     * @param timeoutMs maximum time to wait for the service to connect (default 30 seconds)
     * @throws IllegalStateException if the service does not connect within timeout
     */
    fun enableAccessibilityService(
        container: GenericContainer<*>,
        timeoutMs: Long = 30_000L,
    ) {
        println("[E2E Setup] Enabling accessibility service...")

        val serviceComponent = "$APP_PACKAGE/$ACCESSIBILITY_SERVICE_CLASS"

        execInContainer(
            container,
            "adb", "shell", "settings", "put", "secure",
            "enabled_accessibility_services", serviceComponent
        )

        execInContainer(
            container,
            "adb", "shell", "settings", "put", "secure",
            "accessibility_enabled", "1"
        )

        // Poll dumpsys accessibility to verify the service is actually connected.
        println("[E2E Setup] Waiting for accessibility service to connect (timeout: ${timeoutMs}ms)...")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val dumpsys = container.execInContainer(
                    "sh", "-c",
                    "adb shell dumpsys accessibility | grep -i McpAccessibilityService"
                )
                if (dumpsys.stdout.contains("McpAccessibilityService") &&
                    dumpsys.stdout.contains("Service")
                ) {
                    println("[E2E Setup] Accessibility service connected (${System.currentTimeMillis() - startTime}ms)")
                    return
                }
            } catch (_: Exception) {
                // dumpsys might fail or return empty, keep polling
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        throw IllegalStateException(
            "Accessibility service did not connect within ${timeoutMs}ms. " +
                "Component: $serviceComponent"
        )
    }

    /**
     * Grant MediaProjection permission by triggering the system permission dialog
     * and using adb to click the "Start now" button.
     *
     * MediaProjection requires user consent via a system dialog. In E2E tests,
     * we automate this by:
     * 1. Launching MainActivity with an extra to auto-trigger the permission request
     * 2. Waiting for the system dialog to appear
     * 3. Using uiautomator to find the "Start now" button and tap it
     *
     * @param container the running Docker container
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    fun grantMediaProjectionPermission(container: GenericContainer<*>) {
        println("[E2E Setup] Granting MediaProjection permission...")

        // Step 1: Try to grant via appops (works on some Android versions)
        try {
            container.execInContainer(
                "adb", "shell", "appops", "set", APP_PACKAGE, "PROJECT_MEDIA", "allow"
            )
            println("[E2E Setup] Attempted appops PROJECT_MEDIA grant")
        } catch (e: Exception) {
            println("[E2E Setup] appops grant failed (expected on API 34): ${e.message}")
        }

        // Step 2: Launch MainActivity with extra to trigger MediaProjection dialog
        // Use FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK to fully restart the activity
        // 0x10000000 = FLAG_ACTIVITY_NEW_TASK, 0x00008000 = FLAG_ACTIVITY_CLEAR_TASK
        container.execInContainer(
            "adb", "shell", "am", "start",
            "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS",
            "-f", "0x10008000",  // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
            "--ez", "request_media_projection", "true"
        )
        println("[E2E Setup] Launched MainActivity with request_media_projection=true (NEW_TASK|CLEAR_TASK)")

        // Wait for the permission dialog to appear
        Thread.sleep(3_000)

        // Check if MainActivity received the intent
        try {
            val mainActivityLogs = container.execInContainer(
                "sh", "-c",
                "adb shell logcat -d -t 50 | grep -iE '(MCP:MainActivity|request_media|mediaProjection)'"
            )
            println("[E2E Setup] MainActivity/MediaProjection logs:\n${mainActivityLogs.stdout}")
        } catch (e: Exception) {
            println("[E2E Setup] Could not get MainActivity logs: ${e.message}")
        }

        // Step 3: Use uiautomator to dump the UI and find the "Start now" button
        try {
            val dumpResult = container.execInContainer(
                "adb", "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"
            )
            println("[E2E Setup] uiautomator dump: ${dumpResult.stdout.trim()}")

            val xmlResult = container.execInContainer(
                "adb", "shell", "cat", "/sdcard/window_dump.xml"
            )
            val xml = xmlResult.stdout

            // Debug: Print a portion of the XML to see what's on screen
            println("[E2E Setup] UI dump excerpt (first 2000 chars): ${xml.take(2000)}")

            // Look for "Start now" or "Start" button with bounds attribute
            // Format: bounds="[left,top][right,bottom]"
            val buttonPatterns = listOf("Start now", "Start", "Allow", "OK")
            var tapped = false

            for (pattern in buttonPatterns) {
                if (tapped) break

                // Simple regex to find the button and its bounds
                val regex = Regex("""text="$pattern"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")
                val match = regex.find(xml)
                if (match != null) {
                    val left = match.groupValues[1].toInt()
                    val top = match.groupValues[2].toInt()
                    val right = match.groupValues[3].toInt()
                    val bottom = match.groupValues[4].toInt()

                    // Tap the center of the button
                    val x = (left + right) / 2
                    val y = (top + bottom) / 2

                    println("[E2E Setup] Found '$pattern' button at bounds [$left,$top][$right,$bottom], tapping ($x, $y)")
                    container.execInContainer("adb", "shell", "input", "tap", x.toString(), y.toString())
                    tapped = true
                    Thread.sleep(1_000)
                }
            }

            if (!tapped) {
                println("[E2E Setup] Could not find permission button in UI dump. Trying DPAD navigation...")

                // Fallback: Use keyboard navigation (TAB to button, ENTER to click)
                // On the MediaProjection dialog, the "Start now" button is usually focused
                // or can be reached by pressing TAB/DPAD_RIGHT
                container.execInContainer("adb", "shell", "input", "keyevent", "KEYCODE_TAB")
                Thread.sleep(300)
                container.execInContainer("adb", "shell", "input", "keyevent", "KEYCODE_TAB")
                Thread.sleep(300)
                container.execInContainer("adb", "shell", "input", "keyevent", "KEYCODE_ENTER")
                println("[E2E Setup] Sent TAB+TAB+ENTER for keyboard navigation")
            }
        } catch (e: Exception) {
            println("[E2E Setup] uiautomator approach failed: ${e.message}")

            // Final fallback: Try common button positions
            val fallbackPositions = listOf(
                Pair(850, 1300),  // Lower right for newer dialogs
                Pair(800, 1200),
                Pair(540, 1300),  // Center bottom
            )
            for ((x, y) in fallbackPositions) {
                try {
                    container.execInContainer("adb", "shell", "input", "tap", x.toString(), y.toString())
                    println("[E2E Setup] Fallback tap at ($x, $y)")
                    Thread.sleep(500)
                } catch (tapEx: Exception) {
                    println("[E2E Setup] Fallback tap failed: ${tapEx.message}")
                }
            }
        }

        // Wait for permission to be processed
        Thread.sleep(2_000)

        // Verify by checking if ScreenCaptureService has MediaProjection active
        try {
            val logcat = container.execInContainer(
                "sh", "-c",
                "adb shell logcat -d -t 30 | grep -iE '(MediaProjection|ScreenCapture|setupProjection)'"
            )
            println("[E2E Setup] MediaProjection logcat: ${logcat.stdout.trim()}")
        } catch (e: Exception) {
            println("[E2E Setup] Could not check MediaProjection status: ${e.message}")
        }

        println("[E2E Setup] MediaProjection permission grant attempted")
    }

    /**
     * Configure the MCP server settings for E2E testing.
     *
     * Launches the app activity once to trigger initial DataStore creation,
     * then uses the debug-only BroadcastReceiver to inject test settings
     * (bearer token, binding address, port) via adb broadcast.
     *
     * @param container the running Docker container
     */
    fun configureServerSettings(container: GenericContainer<*>) {
        println("[E2E Setup] Configuring MCP server settings...")

        // Step 1: Launch the app briefly to trigger initial DataStore creation
        execInContainer(
            container,
            "adb", "shell", "am", "start",
            "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS"
        )
        Thread.sleep(5_000)

        // Step 2: Force-stop the app so DataStore files are flushed to disk
        execInContainer(
            container,
            "adb", "shell", "am", "force-stop", APP_PACKAGE
        )
        Thread.sleep(1_000)

        // Step 3: Write settings via adb broadcast to the debug-only E2EConfigReceiver.
        // Use --include-stopped-packages since the app was just force-stopped.
        // Use explicit FQCN for the component to avoid any resolution ambiguity.
        val configAction = "$APP_PACKAGE.E2E_CONFIGURE"
        execInContainer(
            container,
            "adb", "shell", "am", "broadcast",
            "--include-stopped-packages",
            "-a", configAction,
            "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS",
            "--es", "bearer_token", E2E_BEARER_TOKEN,
            "--es", "binding_address", "0.0.0.0",
            "--ei", "port", MCP_DEFAULT_PORT.toString()
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
     *
     * @param container the running Docker container
     */
    fun startMcpServer(container: GenericContainer<*>) {
        println("[E2E Setup] Starting MCP server...")

        // Launch MainActivity to initialize the app
        execInContainer(
            container,
            "adb", "shell", "am", "start",
            "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS"
        )
        Thread.sleep(5_000)

        // Start the McpServerService via broadcast to the debug-only E2EConfigReceiver.
        // This runs inside the app process, bypassing the exported=false restriction.
        val startServerAction = "$APP_PACKAGE.E2E_START_SERVER"
        execInContainer(
            container,
            "adb", "shell", "am", "broadcast",
            "-a", startServerAction,
            "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS"
        )
        Thread.sleep(5_000)

        println("[E2E Setup] MCP server start commands sent")
    }

    /**
     * Poll the /health endpoint until the MCP server is ready.
     *
     * @param container the running Docker container (used for diagnostics on timeout)
     * @param baseUrl the base URL of the MCP server (e.g., "http://localhost:8080")
     * @param bearerToken the bearer token (not used for health, but needed to construct McpClient)
     * @param timeoutMs maximum time to wait for server ready (default 60 seconds)
     * @throws IllegalStateException if server does not become ready within timeout
     */
    fun waitForServerReady(
        container: GenericContainer<*>,
        baseUrl: String,
        bearerToken: String = E2E_BEARER_TOKEN,
        timeoutMs: Long = DEFAULT_SERVER_READY_TIMEOUT_MS,
    ) {
        println("[E2E Setup] Waiting for MCP server to be ready at $baseUrl (timeout: ${timeoutMs}ms)...")

        val client = McpClient(baseUrl, bearerToken)
        val startTime = System.currentTimeMillis()
        var lastError: String? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val health = client.healthCheck()
                val status = health["status"]?.toString()?.removeSurrounding("\"")
                if (status == "healthy") {
                    println("[E2E Setup] MCP server is ready (${System.currentTimeMillis() - startTime}ms)")
                    return
                }
            } catch (e: Exception) {
                val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
                if (errorMsg != lastError) {
                    println("[E2E Setup] Health check poll error: $errorMsg")
                    lastError = errorMsg
                }
            }
            Thread.sleep(HEALTH_POLL_INTERVAL_MS)
        }

        // Dump diagnostics before failing
        dumpDiagnostics(container)

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
     * Dump diagnostic information to help debug server readiness issues.
     * Only called when the health check times out.
     */
    private fun dumpDiagnostics(container: GenericContainer<*>) {
        System.err.println("[E2E Diagnostics] === Server readiness timeout — dumping diagnostics ===")

        try {
            val adbForwardList = container.execInContainer("adb", "forward", "--list")
            System.err.println("[E2E Diagnostics] adb forward list: ${adbForwardList.stdout.trim()}")
        } catch (e: Exception) {
            System.err.println("[E2E Diagnostics] adb forward --list failed: ${e.message}")
        }

        try {
            val ss = container.execInContainer(
                "sh", "-c",
                "adb shell ss -tlnp 2>/dev/null | grep LISTEN"
            )
            System.err.println("[E2E Diagnostics] emulator LISTEN ports: ${ss.stdout.trim()}")
        } catch (e: Exception) {
            System.err.println("[E2E Diagnostics] emulator ss failed: ${e.message}")
        }

        try {
            val logcat = container.execInContainer(
                "sh", "-c",
                "adb shell logcat -d -t 50 | grep -iE '(MCP|E2E|McpServer|FATAL)' | tail -20"
            )
            System.err.println("[E2E Diagnostics] logcat (MCP/E2E): ${logcat.stdout.trim()}")
        } catch (e: Exception) {
            System.err.println("[E2E Diagnostics] logcat failed: ${e.message}")
        }

        System.err.println("[E2E Diagnostics] === End diagnostics dump ===")
    }

    /**
     * Execute a command inside the Docker container and return stdout.
     */
    private fun execInContainer(container: GenericContainer<*>, vararg command: String): String {
        val result = container.execInContainer(*command)
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "[E2E Setup] Command '${command.joinToString(" ")}' failed with exit code " +
                    "${result.exitCode}. stdout: ${result.stdout} stderr: ${result.stderr}"
            )
        }
        return result.stdout
    }
}
