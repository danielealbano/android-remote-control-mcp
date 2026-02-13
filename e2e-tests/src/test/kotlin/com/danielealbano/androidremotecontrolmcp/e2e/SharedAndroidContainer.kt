package com.danielealbano.androidremotecontrolmcp.e2e

import org.testcontainers.containers.GenericContainer

/**
 * Singleton that manages a single shared Docker Android container
 * for all E2E test classes.
 *
 * The container is lazily initialized on first access and reused
 * across all test classes. A JVM shutdown hook stops the container
 * when the test JVM exits.
 *
 * This avoids booting a separate Android emulator container per
 * test class (~2-4 minutes each), reducing total E2E runtime from
 * ~10-15 minutes to ~5-7 minutes.
 */
object SharedAndroidContainer {

    /**
     * Path to the debug APK, relative to the project root.
     * Must be built before running E2E tests: `./gradlew assembleDebug`
     */
    private const val APK_RELATIVE_PATH = "app/build/outputs/apk/debug/app-debug.apk"

    /**
     * Resolved absolute path to the APK, using the project root directory
     * passed as a system property from build.gradle.kts.
     */
    private val apkPath: String by lazy {
        val rootDir = System.getProperty("project.rootDir")
            ?: error("System property 'project.rootDir' not set. Run via Gradle.")
        "$rootDir/$APK_RELATIVE_PATH"
    }

    // Cached values set during successful initialization
    @Volatile
    private var _container: GenericContainer<*>? = null

    @Volatile
    private var _mcpServerUrl: String? = null

    @Volatile
    private var _mcpClient: McpClient? = null

    @Volatile
    private var initError: Throwable? = null

    private val lock = Any()

    /**
     * Initialize the container and all derived values.
     * Called once on first access. Subsequent calls return cached values
     * or rethrow the initialization error.
     */
    private fun ensureInitialized() {
        if (_container != null) return
        if (initError != null) throw initError!!

        synchronized(lock) {
            // Double-check inside lock
            if (_container != null) return
            if (initError != null) throw initError!!

            try {
                println("[SharedAndroidContainer] Initializing shared container...")

                val c = AndroidContainerSetup.createContainer()
                c.start()

                // Wait for emulator boot
                AndroidContainerSetup.waitForEmulatorBoot(c)

                // Set up port forwarding from container to emulator
                AndroidContainerSetup.setupPortForwarding(c)

                // Install APK
                AndroidContainerSetup.installApk(c, apkPath)

                // Install calculator APK for E2E interaction tests
                AndroidContainerSetup.installCalculatorApk(c)

                // Configure server settings (binding 0.0.0.0, known bearer token)
                // NOTE: This step force-stops the app to flush DataStore, which disconnects
                // any accessibility service. Therefore, accessibility must be enabled AFTER
                // this step and after the app process is running.
                AndroidContainerSetup.configureServerSettings(c)

                // Start MCP server (activity + explicit service start)
                AndroidContainerSetup.startMcpServer(c)

                // Wait for server to be ready (polls GET /health until 200 or timeout)
                val url = AndroidContainerSetup.getMcpServerUrl(c)
                AndroidContainerSetup.waitForServerReady(c, url)

                // Enable accessibility service AFTER the app is running.
                // The force-stop in configureServerSettings kills the process and
                // disconnects any previously-bound accessibility service. By enabling
                // after the server is running, the system can immediately bind.
                AndroidContainerSetup.enableAccessibilityService(c)

                // Create and initialize the MCP client
                val client = McpClient(url, AndroidContainerSetup.E2E_BEARER_TOKEN)
                client.initialize()

                // Store all values atomically
                _mcpServerUrl = url
                _mcpClient = client
                _container = c

                println("[SharedAndroidContainer] Container fully initialized and MCP server ready at $url")
            } catch (e: Throwable) {
                initError = e
                throw e
            }
        }
    }

    /**
     * The shared Docker Android container instance.
     */
    val container: GenericContainer<*>
        get() {
            ensureInitialized()
            return _container!!
        }

    /**
     * The base URL of the MCP server, derived from the shared container.
     */
    val mcpServerUrl: String
        get() {
            ensureInitialized()
            return _mcpServerUrl!!
        }

    /**
     * A pre-configured McpClient using the shared container's URL and E2E bearer token.
     */
    val mcpClient: McpClient
        get() {
            ensureInitialized()
            return _mcpClient!!
        }

    init {
        // Register JVM shutdown hook to stop the container when all tests complete.
        Runtime.getRuntime().addShutdownHook(Thread {
            _container?.let { c ->
                println("[SharedAndroidContainer] Stopping shared container...")
                if (c.isRunning) {
                    c.stop()
                }
                println("[SharedAndroidContainer] Shared container stopped")
            }
        })
    }
}
