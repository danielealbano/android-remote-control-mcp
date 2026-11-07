package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ShellProcessTable")
class ShellProcessTableTest {
    private val backend = mockk<ShellBackend>(relaxed = true) { every { kind } returns ShellBackendKind.APP }
    private val table = ShellProcessTable()
    private val registered = mutableListOf<ShellProcess>()
    private val launch = ShellLaunch("cmd", "/", emptyMap(), 30)

    private fun register(config: ShellConfig = ShellConfig()): ShellProcess =
        table.register(launch, backend, config, 0) { registered += it }

    private fun limited(
        limit: ShellLimit,
        value: Int,
    ): ShellConfig = ShellConfig(limits = mapOf(limit to value))

    @Test
    fun `ids increase from 1`() {
        val processes = List(3) { register() }

        assertEquals(listOf(1, 2, 3), processes.map { it.id })
        assertEquals(processes, registered)
    }

    @Test
    fun `a refused register does not call onRegistered`() {
        val config = limited(ShellLimit.MAX_RUNNING_PROCESSES, 1)
        register(config)

        assertThrows<McpToolException.ActionFailed> { register(config) }

        assertEquals(1, registered.size)
    }

    @Test
    fun `running limit refuses`() {
        val config = limited(ShellLimit.MAX_RUNNING_PROCESSES, 2)
        register(config)
        register(config)

        val error = assertThrows<McpToolException.ActionFailed> { register(config) }

        assertTrue(error.message.orEmpty().contains("2 shell processes"), error.message)
    }

    @Test
    fun `finished processes are trimmed oldest first`() {
        val finished = List(5) { register() }
        finished.forEachIndexed { index, process -> process.finish(ShellExit(0), (index + 1) * 10L) }
        val running = register()

        table.trimFinished(2)

        assertEquals(listOf(4, 5, running.id), table.all().map { it.id })
    }

    @Test
    fun `trimming follows the end time, not the start order`() {
        val processes = List(5) { register() }
        processes[0].finish(ShellExit(0), 50)
        processes.drop(1).forEachIndexed { index, process -> process.finish(ShellExit(0), (index + 1) * 10L) }

        table.trimFinished(1)

        assertEquals(listOf(1), table.all().map { it.id })
    }

    @Test
    fun `trimming to zero keeps only running processes`() {
        register().finish(ShellExit(0), 10)
        val running = register()
        register().finish(ShellExit(0), 20)

        table.trimFinished(0)

        assertEquals(listOf(running.id), table.all().map { it.id })
    }

    @Test
    fun `unknown id`() {
        val error = assertThrows<McpToolException.InvalidParams> { table.get(99) }

        assertTrue(error.message.orEmpty().contains("Unknown process_id 99"), error.message)
    }

    @Test
    fun `buffer capacity comes from the config`() {
        val process = register(limited(ShellLimit.OUTPUT_BUFFER_KIB, 64))
        val bytes = ByteArray(100 * KIB) { 'a'.code.toByte() }
        process.append(bytes, bytes.size)

        val head = process.buffer.read(0, 100 * KIB, growing = false)
        val tail = process.buffer.read(head.nextOffset, 100 * KIB, growing = false)

        assertEquals(32L * KIB, head.nextOffset)
        assertTrue(tail.droppedBytes > 0)
    }

    @Test
    fun `clear returns and forgets every process`() {
        val processes = List(2) { register() }

        val cleared = table.clear()

        assertEquals(processes, cleared)
        assertTrue(table.all().isEmpty())
    }

    private companion object {
        const val KIB = 1024
    }
}
