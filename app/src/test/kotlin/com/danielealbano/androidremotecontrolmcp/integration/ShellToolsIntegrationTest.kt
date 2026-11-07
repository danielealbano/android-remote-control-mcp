package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ShellToolContext
import com.danielealbano.androidremotecontrolmcp.services.shell.AppShellBackend
import com.danielealbano.androidremotecontrolmcp.services.shell.FakeShellBackend
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellCommandAuthorizerImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessManagerImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellScriptBuilder
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalNotifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

@EnabledOnOs(OS.LINUX)
@DisplayName("Shell tools integration")
class ShellToolsIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    @Volatile
    private var config = ServerConfig()

    private val fake = FakeShellBackend()
    private val base = McpIntegrationTestHelper.createMockDependencies()
    private val settingsRepository = mockk<SettingsRepository> { coEvery { getServerConfig() } answers { config } }
    private val notifier = mockk<ElevationApprovalNotifier>(relaxed = true) { every { canNotify() } returns true }
    private val manager =
        ShellProcessManagerImpl(
            AppShellBackend("/bin/sh") { pgid -> ProcessBuilder("kill", "-9", "--", "-$pgid").start().waitFor() },
            { fake },
            listOf(fake),
            base.serverLog,
            { config.shellConfig },
            ShellScriptBuilder("/bin/sh", "/usr/bin/setsid", "/usr/bin/id"),
            Dispatchers.IO,
            System::currentTimeMillis,
        )
    private val context =
        ShellToolContext(
            manager,
            ShellCommandAuthorizerImpl(mockk<ElevationApprovalCoordinator>(), notifier, base.serverLog),
            settingsRepository,
            "/scratch",
        )
    private val deps = base.copy(shellToolContext = context)

    @BeforeEach
    fun setUp() {
        manager.open()
    }

    @AfterEach
    fun tearDown() {
        manager.shutdown()
    }

    private fun shell(change: ShellConfig.() -> ShellConfig) {
        config = config.copy(shellConfig = config.shellConfig.change())
    }

    private fun CallToolResult.text(): String = (content.single() as TextContent).text

    private fun CallToolResult.json(): JsonObject =
        Json.parseToJsonElement(text().substringAfter(McpToolUtils.UNTRUSTED_CONTENT_WARNING + "\n")).jsonObject

    private suspend fun Client.exec(
        command: String,
        extra: Map<String, Any> = emptyMap(),
    ): CallToolResult =
        callTool(
            name = "android_shell_exec",
            arguments = mapOf("command" to command, "workdir" to tempDir.absolutePath) + extra,
        )

    @Test
    fun `exec runs a real command over MCP`() =
        runBlocking {
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val json = client.exec("printf ok; exit 2").json()

                assertEquals(2, json["exit_code"]?.jsonPrimitive?.int)
                assertEquals("ok", json["output"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `exec backgrounds at the foreground cap`() =
        runBlocking {
            shell { copy(limits = limits + (ShellLimit.FOREGROUND_WAIT_SECONDS to 10)) }
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val started = client.exec("sleep 12; echo late").json()
                assertEquals("running", started["state"]?.jsonPrimitive?.content)
                val id = started["process_id"]?.jsonPrimitive?.int

                var state: String? = null
                var offset = 0
                val collected = StringBuilder()
                repeat(3) {
                    if (state != "exited") {
                        val page =
                            client
                                .callTool(
                                    name = "android_shell_output",
                                    arguments = mapOf("process_id" to id, "offset" to offset, "wait_seconds" to 10),
                                ).json()
                        state = page["state"]?.jsonPrimitive?.content
                        offset = page["next_offset"]?.jsonPrimitive?.int ?: offset
                        collected.append(page["output"]?.jsonPrimitive?.content.orEmpty())
                    }
                }

                assertEquals("exited", state)
                assertEquals("late\n", collected.toString())
            }
        }

    @Test
    fun `elevated exec runs through the elevated backend`() =
        runBlocking {
            shell { copy(elevationEnabled = true, autoAllowElevation = true) }
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val call =
                    async {
                        client.exec("id", mapOf("elevated" to true, "justification" to "check the uid")).json()
                    }
                withTimeout(WAIT_MS) { while (fake.spawned[1] == null) delay(POLL_MS) }
                val spawned = fake.spawned.getValue(1)
                spawned.writeHeader(5, 2000)
                spawned.write("ok".toByteArray())
                spawned.end(0)

                val json = call.await()

                assertEquals("shizuku", json["backend"]?.jsonPrimitive?.content)
                assertEquals(2000, json["uid"]?.jsonPrimitive?.int)
                assertEquals("ok", json["output"]?.jsonPrimitive?.content)
                withTimeout(WAIT_MS) {
                    while (deps.serverLog.ofType(ServerLogEntry.Type.SHELL).size < 2) delay(POLL_MS)
                }
                val shellLog = deps.serverLog.ofType(ServerLogEntry.Type.SHELL).map { it.message }
                assertTrue(shellLog[0].startsWith("Elevated command auto-allowed, process 1 via shizuku"), shellLog[0])
                assertTrue(shellLog[1].startsWith("Elevated process 1 ended: exited"), shellLog[1])
            }
        }

    @Test
    fun `blocklist refusal is an isError result`() =
        runBlocking {
            shell { copy(blocklist = listOf("*rm *")) }
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.exec("rm -rf x")

                assertEquals(true, result.isError)
                assertTrue(result.text().contains("*rm *"), result.text())
            }
        }

    @Test
    fun `elevation disabled refusal`() =
        runBlocking {
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.exec("id", mapOf("elevated" to true, "justification" to "why"))

                assertEquals(true, result.isError)
                assertTrue(result.text().contains("disabled"), result.text())
            }
        }

    @Test
    fun `invalid params are isError results`() =
        runBlocking {
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_shell_exec", arguments = mapOf("command" to "ls"))

                assertEquals(true, result.isError)
                assertTrue(result.text().contains("workdir"), result.text())
            }
        }

    @Test
    fun `kill and list`() =
        runBlocking {
            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val started =
                    client
                        .callTool(
                            name = "android_shell_start",
                            arguments = mapOf("command" to "sleep 300", "workdir" to tempDir.absolutePath),
                        ).json()
                val id = started["process_id"]?.jsonPrimitive?.int

                val killed = client.callTool(name = "android_shell_kill", arguments = mapOf("process_id" to id)).json()
                val listed = client.callTool(name = "android_shell_list", arguments = emptyMap()).json()

                assertEquals("killed", killed["state"]?.jsonPrimitive?.content)
                val ids = listed["processes"]!!.jsonArray.map { it.jsonObject["process_id"]?.jsonPrimitive?.int }
                assertTrue(ids.contains(id), ids.toString())
            }
        }

    @Test
    fun `tools are listed with the device slug prefix`() =
        runBlocking {
            McpIntegrationTestHelper.withTestApplication(deps, deviceSlug = "pixel7") { client, _ ->
                val names = client.listTools().tools.map { it.name }

                assertTrue(names.contains("android_pixel7_shell_exec"), names.toString())
                assertNotEquals(true, names.contains("android_shell_exec"))
            }
        }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
