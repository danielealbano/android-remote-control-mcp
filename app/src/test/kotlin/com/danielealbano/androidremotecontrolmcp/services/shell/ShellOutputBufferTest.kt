package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ShellOutputBuffer")
class ShellOutputBufferTest {
    private fun ascii(size: Int): ByteArray = ByteArray(size) { ('a' + it % 26).code.toByte() }

    private fun ShellOutputBuffer.append(bytes: ByteArray) = append(bytes, 0, bytes.size)

    @Test
    fun `small output reads back whole`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        buffer.append("0123456789".toByteArray())

        val chunk = buffer.read(0, 64 * KIB, growing = false)

        assertEquals("0123456789", chunk.text)
        assertEquals(10L, chunk.nextOffset)
        assertEquals(0L, chunk.droppedBytes)
    }

    @Test
    fun `paging by offset`() {
        val buffer = ShellOutputBuffer(1024 * KIB)
        val written = ascii(100 * KIB)
        buffer.append(written)

        val first = buffer.read(0, 64 * KIB, growing = false)
        val second = buffer.read(first.nextOffset, 64 * KIB, growing = false)

        assertEquals(0L, first.startOffset)
        assertEquals(64L * KIB, first.nextOffset)
        assertEquals(first.nextOffset, second.startOffset)
        assertEquals(100L * KIB, second.nextOffset)
        assertEquals(String(written, Charsets.US_ASCII), first.text + second.text)
    }

    @Test
    fun `a page spans the head-tail join when nothing was dropped`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        val written = ascii(40 * KIB)
        buffer.append(written)

        val chunk = buffer.read(30L * KIB, 8 * KIB, growing = false)

        assertEquals(String(written, 30 * KIB, 8 * KIB, Charsets.US_ASCII), chunk.text)
        assertEquals(0L, chunk.droppedBytes)
    }

    @Test
    fun `keeps head and tail and reports the gap`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        val written = ByteArray(200 * KIB) { ('a' + (it / 7) % 26).code.toByte() }
        buffer.append(written)
        val total = written.size.toLong()

        val head = buffer.read(0, 64 * KIB, growing = false)
        val tail = buffer.read(32L * KIB, 64 * KIB, growing = false)

        assertEquals(32L * KIB, head.nextOffset)
        assertEquals(String(written, 0, 32 * KIB, Charsets.US_ASCII), head.text)
        assertEquals(total - 32 * KIB, tail.startOffset)
        assertEquals(total - 32 * KIB - 32 * KIB, tail.droppedBytes)
        assertEquals(String(written, written.size - 32 * KIB, 32 * KIB, Charsets.US_ASCII), tail.text)
        assertEquals(total, tail.nextOffset)
    }

    @Test
    fun `ring wraps correctly across many small appends`() {
        val capacity = 64
        val buffer = ShellOutputBuffer(capacity)
        val written = ascii(3 * capacity + 5)
        written.forEach { buffer.append(byteArrayOf(it)) }
        val total = written.size.toLong()

        val tail = buffer.read(total - capacity / 2, capacity, growing = false)

        assertEquals(String(written, written.size - capacity / 2, capacity / 2, Charsets.US_ASCII), tail.text)
    }

    @Test
    fun `offset beyond total is refused`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        buffer.append(ascii(10))

        assertThrows<IllegalArgumentException> { buffer.read(11, 64, growing = false) }
    }

    @Test
    fun `does not split a UTF-8 sequence inside a stretch`() {
        val original = "aé€😀".repeat(100)
        val buffer = ShellOutputBuffer(64 * KIB)
        buffer.append(original.toByteArray(Charsets.UTF_8))
        val total = buffer.totalBytes()

        listOf(5, 6, 7).forEach { pageSize ->
            val pages = StringBuilder()
            var offset = 0L
            while (offset < total) {
                val chunk = buffer.read(offset, pageSize, growing = false)
                assertTrue(chunk.nextOffset > offset, "page size $pageSize stuck at $offset")
                assertTrue(!chunk.text.contains('�'), "page size $pageSize split a sequence at $offset")
                pages.append(chunk.text)
                offset = chunk.nextOffset
            }

            assertEquals(original, pages.toString(), "page size $pageSize")
        }
    }

    @Test
    fun `head end before a gap is not trimmed and paging advances`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        buffer.append("€".repeat(70_000).toByteArray(Charsets.UTF_8))
        val total = buffer.totalBytes()

        var offset = 0L
        var gaps = 0
        var pages = 0
        while (offset < total && pages < MAX_PAGES) {
            val chunk = buffer.read(offset, 32 * KIB, growing = false)
            assertTrue(chunk.nextOffset > offset, "stuck at $offset")
            if (chunk.droppedBytes > 0) gaps++
            offset = chunk.nextOffset
            pages++
        }

        assertEquals(total, offset)
        assertEquals(1, gaps)
    }

    @Test
    fun `a running process page keeps an incomplete last sequence for later`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        val euro = "€".toByteArray(Charsets.UTF_8)
        buffer.append(byteArrayOf('a'.code.toByte(), euro[0]))

        val first = buffer.read(0, 1024, growing = true)
        buffer.append(byteArrayOf(euro[1], euro[2]))
        val second = buffer.read(first.nextOffset, 1024, growing = true)

        assertEquals("a", first.text)
        assertEquals(1L, first.nextOffset)
        assertEquals("€", second.text)
        assertEquals(1L, second.startOffset)
    }

    @Test
    fun `a finished process returns everything`() {
        val buffer = ShellOutputBuffer(64 * KIB)
        val euro = "€".toByteArray(Charsets.UTF_8)
        buffer.append(byteArrayOf('a'.code.toByte(), euro[0]))

        val chunk = buffer.read(0, 1024, growing = false)

        assertEquals("a�", chunk.text)
        assertEquals(buffer.totalBytes(), chunk.nextOffset)
    }

    @Test
    fun `memory grows lazily`() {
        val buffers = List(1000) { ShellOutputBuffer(16 * 1024 * KIB) }

        buffers.forEach { it.append("0123456789".toByteArray()) }

        buffers.forEach { assertEquals("0123456789", it.read(0, 64, growing = false).text) }
    }

    private companion object {
        const val KIB = 1024
        const val MAX_PAGES = 1000
    }
}
