package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ShellToolParams")
class ShellToolParamsTest {
    private val config = ShellConfig()

    private fun args(
        command: String? = "ls",
        workdir: String? = "/sdcard",
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            command?.let { put("command", it) }
            workdir?.let { put("workdir", it) }
            extra.forEach { (name, value) -> put(name, value) }
        }

    private fun refused(
        arguments: JsonObject,
        background: Boolean = false,
    ): String =
        assertThrows<McpToolException.InvalidParams> { ShellToolParams.run(arguments, config, background) }
            .message
            .orEmpty()

    @Test
    fun `minimal exec params`() {
        val params = ShellToolParams.run(args(), config, background = false)

        assertTrue(params.launch.envs.isEmpty())
        assertFalse(params.elevated)
        assertEquals("", params.justification)
        assertNull(params.launch.elevation)
        assertEquals(30, params.launch.timeoutMinutes)
    }

    @Test
    fun `missing or blank command or workdir`() {
        assertTrue(refused(args(command = null)).contains("'command'"))
        assertTrue(refused(args(command = "  ")).contains("'command'"))
        assertTrue(refused(args(workdir = null)).contains("'workdir'"))
        assertTrue(refused(args(workdir = "")).contains("'workdir'"))
    }

    @Test
    fun `relative workdir refused`() {
        assertTrue(refused(args(workdir = "tmp")).contains("absolute path"))
    }

    @Test
    fun `NUL characters refused`() {
        refused(args(command = "ls\u0000x"))
        refused(args(workdir = "/sd\u0000card"))
        refused(args(extra = mapOf("envs" to buildJsonObject { put("A", "x\u0000y") })))
    }

    private fun elevated(justification: String): JsonObject =
        args(extra = mapOf("elevated" to JsonPrimitive(true), "justification" to JsonPrimitive(justification)))

    @Test
    fun `elevated needs a justification`() {
        val message = refused(elevated(" "))

        assertTrue(message.contains("'justification'"), message)
    }

    @Test
    fun `justification is at most 1000 characters`() {
        val accepted = ShellToolParams.run(elevated("j".repeat(1000)), config, background = false)

        assertEquals(1000, accepted.justification.length)
        refused(args(extra = mapOf("justification" to JsonPrimitive("j".repeat(1001)))))
    }

    @Test
    fun `envs parsing`() {
        val envs =
            buildJsonObject {
                put("A", "1")
                put("_b", "x y")
            }

        val params = ShellToolParams.run(args(extra = mapOf("envs" to envs)), config, background = false)

        assertEquals(mapOf("A" to "1", "_b" to "x y"), params.launch.envs)
        refused(args(extra = mapOf("envs" to buildJsonArray { })))
        refused(args(extra = mapOf("envs" to buildJsonObject { put("A", 1) })))
        refused(args(extra = mapOf("envs" to buildJsonObject { put("1A", "x") })))
        refused(args(extra = mapOf("envs" to buildJsonObject { putJsonObject("A") { } })))
    }

    @Test
    fun `timeout_minutes only for start`() {
        val ignored = ShellToolParams.run(args(extra = mapOf("timeout_minutes" to JsonPrimitive(5))), config, false)
        val first = ShellToolParams.run(args(extra = mapOf("timeout_minutes" to JsonPrimitive(1))), config, true)
        val last = ShellToolParams.run(args(extra = mapOf("timeout_minutes" to JsonPrimitive(120))), config, true)
        val default = ShellToolParams.run(args(), config, true)

        assertEquals(30, ignored.launch.timeoutMinutes)
        assertEquals(1, first.launch.timeoutMinutes)
        assertEquals(120, last.launch.timeoutMinutes)
        assertEquals(config.backgroundTimeoutDefaultMinutes, default.launch.timeoutMinutes)
        refused(args(extra = mapOf("timeout_minutes" to JsonPrimitive(0))), background = true)
        refused(args(extra = mapOf("timeout_minutes" to JsonPrimitive(121))), background = true)
    }

    @Test
    fun `script size limit counts quoting`() {
        val accepted = ShellToolParams.run(args(command = "x".repeat(90_000)), config, background = false)

        assertEquals(90_000, accepted.launch.command.length)
        assertTrue(refused(args(command = "'".repeat(30_000))).contains("too long together"))
    }

    @Test
    fun `wait_seconds range`() {
        fun wait(value: Int): Int = ShellToolParams.waitSeconds(buildJsonObject { put("wait_seconds", value) }, config)

        assertEquals(0, wait(0))
        assertEquals(90, wait(90))
        assertThrows<McpToolException.InvalidParams> { wait(-1) }
        assertThrows<McpToolException.InvalidParams> { wait(91) }
    }
}
