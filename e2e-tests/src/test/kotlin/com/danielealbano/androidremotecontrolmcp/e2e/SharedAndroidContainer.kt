package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking

/**
 * Singleton that manages a single shared redroid Docker container
 * for all E2E test classes.
 *
 * The container is lazily initialized on first access and reused
 * across all test classes. A JVM shutdown hook stops the container
 * when the test JVM exits.
 *
 * This avoids booting a separate Android container per test class,
 * reducing total E2E runtime significantly.
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
    private var _container: org.testcontainers.containers.GenericContainer<*>? = null

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

                // Wait for redroid boot via host-side ADB
                AndroidContainerSetup.waitForEmulatorBoot(c)

                // Install APK
                AndroidContainerSetup.installApk(apkPath)

                // Install calculator APK for E2E interaction tests
                AndroidContainerSetup.installCalculatorApk()

                // Grant camera and microphone permissions for camera E2E tests
                AndroidContainerSetup.grantCameraPermissions()

                // Configure server settings (binding 0.0.0.0, known bearer token)
                // NOTE: This step force-stops the app to flush DataStore, which disconnects
                // any accessibility service. Therefore, accessibility must be enabled AFTER
                // this step and after the app process is running.
                AndroidContainerSetup.configureServerSettings()

                // Start MCP server (activity + explicit service start)
                AndroidContainerSetup.startMcpServer()

                // Wait for server to be ready (polls HTTP POST to /mcp until responsive)
                val url = AndroidContainerSetup.getMcpServerUrl(c)
                AndroidContainerSetup.waitForServerReady(url)

                // Enable accessibility service AFTER the app is running.
                // The force-stop in configureServerSettings kills the process and
                // disconnects any previously-bound accessibility service. By enabling
                // after the server is running, the system can immediately bind.
                AndroidContainerSetup.enableAccessibilityService()

                // Create and connect the MCP client
                val client = McpClient(url, AndroidContainerSetup.E2E_BEARER_TOKEN)
                runBlocking { client.connect() }

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

    /**
     * Ensures the accessibility service is connected, re-enabling it if necessary.
     *
     * Intended to be called from `@BeforeEach` in E2E test classes to recover
     * from transient accessibility service disconnections.
     */
    fun ensureAccessibilityService() {
        ensureInitialized()
        AndroidContainerSetup.ensureAccessibilityService()
    }

    init {
        // Register JVM shutdown hook to stop the container when all tests complete.
        Runtime.getRuntime().addShutdownHook(Thread {
            _container?.let { c ->
                println("[SharedAndroidContainer] Stopping shared container...")
                AndroidContainerSetup.disconnectAdb()
                if (c.isRunning) {
                    c.stop()
                }
                println("[SharedAndroidContainer] Shared container stopped")
            }
        })
    }
}
