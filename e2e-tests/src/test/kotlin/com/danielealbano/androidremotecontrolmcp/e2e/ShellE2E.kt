package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object ShellE2E {
    const val JUSTIFICATION = "e2e test of elevated commands"
    const val TEST_FOREGROUND_SECONDS = 60
    const val TEST_APPROVAL_SECONDS = 30
    private const val CONFIGURE_WAIT_MS = 3_000L
    private const val READY_TIMEOUT_MS = 30_000L
    private const val READY_POLL_MS = 1_000L
    private const val END_POLL_SECONDS = 10
    private const val END_POLLS = 9
    private const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private val DEFAULT_LIMITS =
        listOf(
            "shell_max_running_processes" to 16,
            "shell_max_finished_processes" to 32,
            "shell_background_timeout_max_minutes" to 120,
            "shell_approval_timeout_seconds" to 60,
            "shell_foreground_wait_seconds" to 90,
            "shell_output_per_call_kib" to 64,
            "shell_output_buffer_kib" to 1024,
        )

    val scratch: String
        get() = "/storage/emulated/0/Android/data/${AndroidContainerSetup.APP_PACKAGE}/cache/shell"

    fun configure(vararg extras: String) {
        AndroidContainerSetup.execAdb(
            "shell",
            "am",
            "broadcast",
            "-a",
            AndroidContainerSetup.E2E_CONFIGURE_ACTION,
            "-n",
            AndroidContainerSetup.E2E_RECEIVER_COMPONENT,
            *extras,
        )
        Thread.sleep(CONFIGURE_WAIT_MS)
    }

    fun patterns(
        extra: String,
        patterns: List<String>,
    ): Array<String> = arrayOf("--es", extra, quoteForDevice(JsonArray(patterns.map(::JsonPrimitive)).toString()))

    fun switches(
        elevation: Boolean,
        autoAllow: Boolean,
    ): Array<String> =
        arrayOf(
            "--ez",
            "shell_elevation_enabled",
            "$elevation",
            "--ez",
            "shell_auto_allow_elevation",
            "$autoAllow",
        )

    fun testTimeouts(): Array<String> =
        arrayOf(
            "--ei",
            "shell_foreground_wait_seconds",
            "$TEST_FOREGROUND_SECONDS",
            "--ei",
            "shell_approval_timeout_seconds",
            "$TEST_APPROVAL_SECONDS",
        )

    fun restoreDefaults() {
        configure(
            *switches(elevation = false, autoAllow = false),
            *patterns("shell_blocklist", emptyList()),
            *patterns("shell_allowlist", emptyList()),
            *DEFAULT_LIMITS.flatMap { (key, value) -> listOf("--ei", key, "$value") }.toTypedArray(),
        )
        configure("--ei", "shell_background_timeout_default_minutes", "30")
    }

    fun run(
        command: String,
        elevated: Boolean = false,
        workdir: String = scratch,
        envs: Map<String, String> = emptyMap(),
    ): Map<String, Any?> =
        buildMap {
            put("command", command)
            put("workdir", workdir)
            if (envs.isNotEmpty()) put("envs", envs)
            if (elevated) {
                put("elevated", true)
                put("justification", JUSTIFICATION)
            }
        }

    suspend fun call(
        tool: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): JsonObject {
        val result = callRaw(tool, arguments)
        check(result.isError != true) { "$tool failed: ${text(result)}" }
        return Json.parseToJsonElement(stripUntrustedWarning(text(result))).jsonObject
    }

    suspend fun callError(
        tool: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): String {
        val result = callRaw(tool, arguments)
        check(result.isError == true) { "$tool unexpectedly succeeded: ${text(result)}" }
        return text(result)
    }

    suspend fun awaitElevatedReady(
        backend: String = "shizuku",
        workdir: String = scratch,
    ) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val result = callRaw("shell_exec", run("true", elevated = true, workdir = workdir))
            last = text(result)
            if (result.isError != true && ranCleanlyThrough(last, backend)) return
            Thread.sleep(READY_POLL_MS)
        }
        error("Elevated commands not ready through $backend within ${READY_TIMEOUT_MS}ms: $last")
    }

    private fun ranCleanlyThrough(
        result: String,
        backend: String,
    ): Boolean {
        val json = Json.parseToJsonElement(stripUntrustedWarning(result)).jsonObject
        return json.getValue("backend").jsonPrimitive.content == backend &&
            json.getValue("exit_code").jsonPrimitive.intOrNull == 0
    }

    suspend fun awaitElevatedError(expected: String): String {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val result = callRaw("shell_exec", run("id -u", elevated = true))
            last = text(result)
            if (result.isError == true && last.contains(expected)) return last
            Thread.sleep(READY_POLL_MS)
        }
        error("No elevated error containing '$expected' within ${READY_TIMEOUT_MS}ms, last: $last")
    }

    suspend fun awaitEnd(processId: Int): JsonObject {
        var offset = 0L
        val output = StringBuilder()
        repeat(END_POLLS) {
            val page =
                call(
                    "shell_output",
                    mapOf("process_id" to processId, "offset" to offset, "wait_seconds" to END_POLL_SECONDS),
                )
            output.append(page.getValue("output").jsonPrimitive.content)
            offset = page.getValue("next_offset").jsonPrimitive.long
            if (page.getValue("state").jsonPrimitive.content != "running") {
                return JsonObject(page + ("output" to JsonPrimitive(output.toString())))
            }
        }
        error("Process $processId still running after ${END_POLLS * END_POLL_SECONDS} seconds")
    }

    fun appUid(): Int = StorageE2E.shell("stat -c %u /data/data/${AndroidContainerSetup.APP_PACKAGE}").trim().toInt()

    fun grantNotifications() {
        AndroidContainerSetup.execAdb("shell", "pm", "grant", AndroidContainerSetup.APP_PACKAGE, NOTIFICATIONS)
    }

    fun revokeNotifications() {
        AndroidContainerSetup.execAdb("shell", "pm", "revoke", AndroidContainerSetup.APP_PACKAGE, NOTIFICATIONS)
    }

    fun restartServer(): McpClient {
        AndroidContainerSetup.stopMcpServer(SharedAndroidContainer.mcpServerUrl)
        return StorageE2E.restartServerAndRefreshClient()
    }

    private suspend fun callRaw(
        tool: String,
        arguments: Map<String, Any?>,
    ): CallToolResult {
        val name = "${AndroidContainerSetup.TOOL_NAME_PREFIX}$tool"
        return SharedAndroidContainer.mcpClient.callTool(name, arguments)
    }

    private fun text(result: CallToolResult): String = (result.content.first() as TextContent).text

    private fun quoteForDevice(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
