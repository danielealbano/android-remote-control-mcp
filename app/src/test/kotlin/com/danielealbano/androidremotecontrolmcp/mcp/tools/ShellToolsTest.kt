package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.shell.ElevationGrant
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellBackendKind
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellCommandAuthorizer
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellElevation
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellLaunch
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellOutputChunk
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessManager
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessPage
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessSnapshot
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessState
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.TimeSource

@DisplayName("Shell tools")
class ShellToolsTest {
    private val processManager = mockk<ShellProcessManager>()
    private val authorizer = mockk<ShellCommandAuthorizer>()
    private val settingsRepository =
        mockk<SettingsRepository> {
            coEvery { getServerConfig() } returns ServerConfig(shellConfig = ShellConfig())
        }

    private fun context(timeSource: TimeSource = TimeSource.Monotonic): ShellToolContext =
        ShellToolContext(processManager, authorizer, settingsRepository, "/scratch", timeSource)

    private fun snapshot(
        id: Int = 1,
        state: ShellProcessState = ShellProcessState.EXITED,
        command: String = "ls",
    ): ShellProcessSnapshot =
        ShellProcessSnapshot(
            id = id,
            command = command,
            backend = ShellBackendKind.APP,
            elevated = false,
            uid = 10_123,
            state = state,
            exitCode = if (state == ShellProcessState.EXITED) 0 else null,
            failure = null,
            startedAtMs = 1,
            endedAtMs = 2,
            totalBytes = 3,
        )

    private fun page(snapshot: ShellProcessSnapshot = snapshot()): ShellProcessPage =
        ShellProcessPage(snapshot, ShellOutputChunk("ok\n", 0, 3, 0, 3))

    private fun runArgs(
        elevated: Boolean = false,
        justification: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("command", "ls")
            put("workdir", "/sdcard")
            if (elevated) put("elevated", true)
            justification?.let { put("justification", it) }
        }

    private fun CallToolResult.text(): String = (content.single() as TextContent).text

    private fun CallToolResult.json(): JsonObject =
        Json.parseToJsonElement(text().substringAfter(McpToolUtils.UNTRUSTED_CONTENT_WARNING + "\n")).jsonObject

    @Test
    fun `exec returns the untrusted JSON of the page`() =
        runTest {
            val context = context(testScheduler.timeSource)
            coEvery { authorizer.authorize(any(), any(), any()) } returns null
            coEvery { processManager.startAndAwait(any(), any(), any(), any()) } returns page()

            val result = ShellRunHandler(context, background = false).execute(runArgs())

            assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, result.text().lines().first())
            val json = result.json()
            assertEquals(1, json["process_id"]?.jsonPrimitive?.int)
            assertEquals("exited", json["state"]?.jsonPrimitive?.content)
            assertEquals(0, json["exit_code"]?.jsonPrimitive?.int)
            assertEquals("app", json["backend"]?.jsonPrimitive?.content)
            assertEquals(10_123, json["uid"]?.jsonPrimitive?.int)
            assertEquals("ok\n", json["output"]?.jsonPrimitive?.content)
            assertEquals(3, json["next_offset"]?.jsonPrimitive?.int)
            assertEquals("false", json["has_more_output"]?.jsonPrimitive?.content)
        }

    @Test
    fun `exec waits within the budget`() =
        runTest {
            val context = context(testScheduler.timeSource)
            coEvery { authorizer.authorize(any(), any(), any()) } coAnswers {
                delay(5_000)
                null
            }
            coEvery { processManager.startAndAwait(any(), any(), any(), any()) } returns page()

            ShellRunHandler(context, background = false).execute(runArgs())

            coVerify(exactly = 1) { processManager.startAndAwait(any(), any(), 85_000, 65_536) }
        }

    @Test
    fun `exec passes the elevation grant`() =
        runTest {
            val context = context(testScheduler.timeSource)
            val launch = slot<ShellLaunch>()
            coEvery { authorizer.authorize(any(), any(), any()) } returns ElevationGrant.ALLOWLISTED
            coEvery { processManager.startAndAwait(capture(launch), any(), any(), any()) } returns page()

            ShellRunHandler(context, background = false).execute(runArgs(elevated = true, justification = "why"))

            assertEquals(ShellElevation("why", ElevationGrant.ALLOWLISTED), launch.captured.elevation)
        }

    @Test
    fun `start does not wait`() =
        runTest {
            val context = context(testScheduler.timeSource)
            coEvery { authorizer.authorize(any(), any(), any()) } returns null
            coEvery { processManager.startAndAwait(any(), any(), any(), any()) } returns page()

            ShellRunHandler(context, background = true).execute(runArgs())

            coVerify(exactly = 1) { processManager.startAndAwait(any(), any(), 0, any()) }
        }

    @Test
    fun `refusal propagates`() =
        runTest {
            val context = context(testScheduler.timeSource)
            coEvery { authorizer.authorize(any(), any(), any()) } throws McpToolException.PermissionDenied("refused")

            val handler = ShellRunHandler(context, background = false)

            val error = runCatching { handler.execute(runArgs()) }.exceptionOrNull()

            assertTrue(error is McpToolException.PermissionDenied, error.toString())
            coVerify(exactly = 0) { processManager.startAndAwait(any(), any(), any(), any()) }
        }

    @Test
    fun `output waits only when asked`() =
        runTest {
            val context = context()
            coEvery { processManager.page(any(), any(), any(), any()) } returns page()
            val handler = ShellOutputHandler(context)

            handler.execute(buildJsonObject { put("process_id", 1) })
            handler.execute(
                buildJsonObject {
                    put("process_id", 1)
                    put("wait_seconds", 5)
                },
            )

            coVerify(exactly = 1) { processManager.page(1, 0, 0, any()) }
            coVerify(exactly = 1) { processManager.page(1, 0, 5_000, any()) }
        }

    @Test
    fun `output reads from the offset with the per-call limit`() =
        runTest {
            val context = context()
            coEvery { processManager.page(any(), any(), any(), any()) } returns page()

            ShellOutputHandler(context).execute(
                buildJsonObject {
                    put("process_id", 1)
                    put("offset", 70_000)
                },
            )

            coVerify(exactly = 1) { processManager.page(1, 70_000, 0, 65_536) }
        }

    @Test
    fun `kill returns the summary`() =
        runTest {
            val context = context()
            coEvery { processManager.killAndAwait(any(), any()) } returns snapshot(state = ShellProcessState.KILLED)

            val result = ShellKillHandler(context).execute(buildJsonObject { put("process_id", 1) })

            coVerify(exactly = 1) { processManager.killAndAwait(1, 2_000) }
            assertEquals("killed", result.json()["state"]?.jsonPrimitive?.content)
            assertEquals("ls", result.json()["command"]?.jsonPrimitive?.content)
        }

    @Test
    fun `list returns every process`() {
        val context = context()
        every { processManager.list() } returns listOf(snapshot(1, command = "x".repeat(300)), snapshot(2))

        val processes = ShellListHandler(context).execute().json()["processes"]!!.jsonArray

        assertEquals(2, processes.size)
        assertEquals("x".repeat(200), processes[0].jsonObject["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `registerShellTools honours tool switches`() {
        val server = mockk<Server>(relaxed = true)
        val names = mutableListOf<String>()
        every {
            server.addTool(name = capture(names), description = any(), inputSchema = any(), handler = any())
        } returns Unit
        val registrar =
            LoggedToolRegistrar(server, RecordingServerLogRepository(), inFlightToolCalls = InFlightToolCalls())
        val context = context()

        registerShellTools(registrar, context, "android_", ToolPermissionsConfig(disabledTools = setOf("shell_kill")))

        assertEquals(
            listOf("android_shell_exec", "android_shell_start", "android_shell_output", "android_shell_list"),
            names,
        )
    }

    @Test
    fun `descriptions name the scratch path and noexec`() {
        val withPath = ShellToolDescriptions.run(false, "/storage/emulated/0/Android/data/x/cache/shell", "android_")
        val withoutPath = ShellToolDescriptions.run(false, null, "android_")

        assertTrue(withPath.contains("/storage/emulated/0/Android/data/x/cache/shell"))
        assertTrue(withPath.contains("noexec"))
        assertTrue(withPath.contains("/data/local/tmp"))
        assertTrue(withoutPath.contains("No scratch folder"))
    }

    @Test
    fun `input schemas`() {
        val exec = ShellToolDescriptions.runSchema(false)
        val start = ShellToolDescriptions.runSchema(true)
        val output = ShellToolDescriptions.outputSchema("android_")
        val kill = ShellToolDescriptions.killSchema()
        val empty = ShellToolDescriptions.emptySchema()

        assertEquals(setOf("command", "workdir", "envs", "elevated", "justification"), exec.properties!!.keys)
        val envs = exec.properties!!["envs"]!!.jsonObject
        assertEquals("object", envs["type"]?.jsonPrimitive?.content)
        assertEquals(
            "string",
            envs["additionalProperties"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(exec.properties!!.keys + "timeout_minutes", start.properties!!.keys)
        assertEquals(listOf("command", "workdir"), exec.required)
        assertEquals(listOf("command", "workdir"), start.required)
        assertEquals(setOf("process_id", "offset", "wait_seconds"), output.properties!!.keys)
        assertEquals(listOf("process_id"), output.required)
        assertEquals(listOf("process_id"), kill.required)
        assertTrue(empty.properties!!.isEmpty())
        assertTrue(empty.required.orEmpty().isEmpty())
    }

    @Test
    fun `descriptions use the prefixed tool names`() {
        val prefix = "android_pixel7_"
        val texts =
            listOf(
                ShellToolDescriptions.run(false, "/scratch", prefix),
                ShellToolDescriptions.run(true, "/scratch", prefix),
                ShellToolDescriptions.output(prefix),
                ShellToolDescriptions.kill(prefix),
                ShellToolDescriptions.outputSchema(prefix).properties.toString(),
            )
        val bare = Regex("(?<![a-z0-9_])shell_(exec|start|output|kill)")

        assertTrue(texts[0].contains("android_pixel7_shell_output") && texts[0].contains("android_pixel7_shell_kill"))
        assertTrue(texts[2].contains("android_pixel7_shell_exec") && texts[2].contains("android_pixel7_shell_start"))
        assertTrue(texts[3].contains("android_pixel7_shell_exec"))
        assertTrue(texts[4].contains("android_pixel7_shell_start"))
        texts.forEach { assertNull(bare.find(it), it) }
    }

    @Test
    fun `descriptions state elevation, paging and leftover cleanup`() {
        val exec = ShellToolDescriptions.run(false, "/scratch", "android_")
        val start = ShellToolDescriptions.run(true, "/scratch", "android_")

        listOf(exec, start).forEach { description ->
            assertTrue(description.contains("'elevated': true"))
            assertTrue(description.contains("justification"))
            assertTrue(description.contains("next_offset"))
            assertTrue(description.contains("process group is stopped"))
        }
        assertTrue(start.contains("waits for that answer"))
        assertFalse(exec.contains("waits for that answer"))
    }
}
