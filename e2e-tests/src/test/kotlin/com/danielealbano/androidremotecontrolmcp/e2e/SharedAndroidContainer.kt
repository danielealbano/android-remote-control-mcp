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
     * Path to the debug APK. Relative to the project root.
     * Must be built before running E2E tests: `./gradlew assembleDebug`
     */
    private const val APK_PATH = "app/build/outputs/apk/debug/app-debug.apk"

    /**
     * Lazy delegate for the container so we can check initialization state
     * in the shutdown hook without triggering container creation.
     */
    private val containerDelegate = lazy {
        val c = AndroidContainerSetup.createContainer()
        c.start()

        // Wait for emulator boot
        AndroidContainerSetup.waitForEmulatorBoot(c)

        // Set up port forwarding from container to emulator
        AndroidContainerSetup.setupPortForwarding(c)

        // Install APK
        AndroidContainerSetup.installApk(c, APK_PATH)

        // Enable accessibility service
        AndroidContainerSetup.enableAccessibilityService(c)

        // Configure server settings (binding 0.0.0.0, known bearer token)
        AndroidContainerSetup.configureServerSettings(c)

        // Start MCP server (activity + explicit service start)
        AndroidContainerSetup.startMcpServer(c)

        // Wait for server to be ready (polls GET /health until 200 or timeout)
        val url = AndroidContainerSetup.getMcpServerUrl(c)
        AndroidContainerSetup.waitForServerReady(url)

        println("[SharedAndroidContainer] Container fully initialized and MCP server ready at $url")

        c
    }

    /**
     * The shared Docker Android container instance.
     * Lazily initialized on first access.
     */
    val container: GenericContainer<*> by containerDelegate

    /**
     * The base URL of the MCP server, derived from the shared container.
     */
    val mcpServerUrl: String by lazy {
        AndroidContainerSetup.getMcpServerUrl(container)
    }

    /**
     * A pre-configured McpClient using the shared container's URL and E2E bearer token.
     */
    val mcpClient: McpClient by lazy {
        val client = McpClient(mcpServerUrl, AndroidContainerSetup.E2E_BEARER_TOKEN)
        client.initialize()
        client
    }

    init {
        // Register JVM shutdown hook to stop the container when all tests complete.
        // Only stop if the lazy delegate was actually initialized to avoid triggering
        // container creation during JVM shutdown.
        Runtime.getRuntime().addShutdownHook(Thread {
            if (containerDelegate.isInitialized()) {
                println("[SharedAndroidContainer] Stopping shared container...")
                if (container.isRunning) {
                    container.stop()
                }
                println("[SharedAndroidContainer] Shared container stopped")
            }
        })
    }
}
