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

    private const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.debug"
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
                Wait.forLogMessage(".*Boot completed.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(300))
            )
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig?.withMemory(memoryBytes)
                cmd.hostConfig?.withMemorySwap(memoryBytes)
            }
    }

    /**
     * Set up adb port forwarding inside the Docker container.
     *
     * The MCP server runs inside the Android emulator, which has its own
     * network stack. Testcontainers maps the Docker container's port to the
     * test host, but we also need to forward from the container's localhost
     * to the emulator's port. This is done via `adb forward` inside the
     * container.
     *
     * Forward chain:
     *   test host:randomPort <-> container:MCP_DEFAULT_PORT <-> emulator:MCP_DEFAULT_PORT
     *
     * @param container the running Docker container
     */
    fun setupPortForwarding(container: GenericContainer<*>) {
        println("[E2E Setup] Setting up adb port forwarding (container:$MCP_DEFAULT_PORT -> emulator:$MCP_DEFAULT_PORT)...")

        val result = execInContainer(
            container,
            "adb", "forward", "tcp:$MCP_DEFAULT_PORT", "tcp:$MCP_DEFAULT_PORT"
        )
        println("[E2E Setup] Port forwarding established: $result")
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
     * @param container the running Docker container
     */
    fun enableAccessibilityService(container: GenericContainer<*>) {
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

        // Allow some time for the service to start
        Thread.sleep(2_000)

        println("[E2E Setup] Accessibility service enabled")
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
        println("[E2E Setup] Configuring MCP server settings for E2E testing...")

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

        // Step 3: Write settings via adb broadcast to the debug-only E2EConfigReceiver
        val configAction = "$APP_PACKAGE.E2E_CONFIGURE"
        execInContainer(
            container,
            "adb", "shell", "am", "broadcast",
            "-a", configAction,
            "-n", "$APP_PACKAGE/.E2EConfigReceiver",
            "--es", "bearer_token", E2E_BEARER_TOKEN,
            "--es", "binding_address", "0.0.0.0",
            "--ei", "port", MCP_DEFAULT_PORT.toString()
        )
        Thread.sleep(1_000)

        println("[E2E Setup] Server settings configured via broadcast intent")
    }

    /**
     * Start the MCP server by launching the MainActivity and then explicitly
     * starting the McpServerService via adb shell am startservice.
     *
     * The activity launch triggers the UI and may auto-start the service,
     * but we also send an explicit startservice command to ensure the server
     * foreground service is running regardless of auto-start settings.
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
        Thread.sleep(3_000)

        // Explicitly start the McpServerService foreground service
        val serviceClass = "$APP_PACKAGE/${APP_PACKAGE.removeSuffix(".debug")}.services.mcp.McpServerService"
        execInContainer(
            container,
            "adb", "shell", "am", "startservice",
            "-n", serviceClass
        )
        Thread.sleep(2_000)

        println("[E2E Setup] MCP server start commands sent (activity + service)")
    }

    /**
     * Poll the /health endpoint until the MCP server is ready.
     *
     * @param baseUrl the base URL of the MCP server (e.g., "http://localhost:8080")
     * @param bearerToken the bearer token (not used for health, but needed to construct McpClient)
     * @param timeoutMs maximum time to wait for server ready (default 60 seconds)
     * @throws IllegalStateException if server does not become ready within timeout
     */
    fun waitForServerReady(
        baseUrl: String,
        bearerToken: String = E2E_BEARER_TOKEN,
        timeoutMs: Long = DEFAULT_SERVER_READY_TIMEOUT_MS,
    ) {
        println("[E2E Setup] Waiting for MCP server to be ready at $baseUrl (timeout: ${timeoutMs}ms)...")

        val client = McpClient(baseUrl, bearerToken)
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val health = client.healthCheck()
                val status = health["status"]?.toString()?.removeSurrounding("\"")
                if (status == "healthy") {
                    println("[E2E Setup] MCP server is ready (${System.currentTimeMillis() - startTime}ms)")
                    return
                }
            } catch (_: Exception) {
                // Server not ready yet, continue polling
            }
            Thread.sleep(HEALTH_POLL_INTERVAL_MS)
        }

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
     * Execute a command inside the Docker container and return stdout.
     */
    private fun execInContainer(container: GenericContainer<*>, vararg command: String): String {
        val result = container.execInContainer(*command)
        if (result.exitCode != 0) {
            if (result.stderr.isNotEmpty()) {
                println("[E2E Setup] Command '${command.joinToString(" ")}' stderr: ${result.stderr}")
            }
            throw IllegalStateException(
                "[E2E Setup] Command '${command.joinToString(" ")}' failed with exit code " +
                    "${result.exitCode}. stdout: ${result.stdout} stderr: ${result.stderr}"
            )
        }
        return result.stdout
    }
}
