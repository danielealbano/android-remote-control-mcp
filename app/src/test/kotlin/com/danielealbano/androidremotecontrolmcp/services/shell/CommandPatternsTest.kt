package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

@DisplayName("CommandPatterns")
class CommandPatternsTest {
    private fun blocked(
        pattern: String,
        command: String,
    ): Boolean = CommandPatterns.firstBlocklistMatch(listOf(pattern), command) != null

    private fun allowed(
        pattern: String,
        command: String,
    ): Boolean = CommandPatterns.firstAllowlistMatch(listOf(pattern), command) != null

    @Test
    fun `blocklist star matches anywhere`() {
        assertEquals(true, blocked("*reboot*", "reboot"))
        assertEquals(true, blocked("*reboot*", "id; reboot"))
        assertEquals(true, blocked("*reboot*", "echo x\nreboot now"))
    }

    @Test
    fun `blocklist is whole-command and case-sensitive`() {
        assertEquals(true, blocked("reboot", "  reboot  "))
        assertEquals(false, blocked("reboot", "reboot -p"))
        assertEquals(false, blocked("reboot", "REBOOT"))
    }

    @Test
    fun `allowlist prefix pattern`() {
        assertEquals(true, allowed("ls -* /dev", "ls -la /dev"))
        assertEquals(false, allowed("ls -* /dev", "ls -la /data"))
    }

    @Test
    fun `allowlist star never crosses separators`() {
        listOf(";", "&&", "|", "`", "$(", "<", ">", "\r", "\n").forEach { separator ->
            val command = "ls -la$separator reboot$separator ls - /dev"

            assertEquals(false, allowed("ls -* /dev", command), "separator ${separator.toList()}")
        }
    }

    @Test
    fun `allowlist literal special characters in the pattern are matched literally`() {
        assertEquals(true, allowed("echo a;b", "echo a;b"))
    }

    @Test
    fun `regex metacharacters are literal`() {
        assertEquals(true, allowed("a.b", "a.b"))
        assertEquals(false, allowed("a.b", "axb"))
        assertEquals(true, allowed("ls [x]", "ls [x]"))
        assertEquals(false, allowed("ls [x]", "ls x"))
    }

    @Test
    fun `blank patterns never match`() {
        listOf("", "  ").forEach { pattern ->
            assertEquals(false, blocked(pattern, ""))
            assertEquals(false, blocked(pattern, "  "))
            assertEquals(false, allowed(pattern, "ls"))
        }
    }

    @Test
    fun `first matching pattern is returned`() {
        assertEquals("id*", CommandPatterns.firstBlocklistMatch(listOf("id*", "*"), "id -u"))
        assertEquals("id*", CommandPatterns.firstAllowlistMatch(listOf("id*", "*"), "id -u"))
        assertNull(CommandPatterns.firstAllowlistMatch(listOf("ls*"), "id -u"))
    }

    @Test
    fun `literal parts never overlap`() {
        assertEquals(false, blocked("ab*ba", "aba"))
        assertEquals(true, blocked("a*a", "aa"))
        assertEquals(true, blocked("**", "anything at all"))
        assertEquals(true, allowed("**", "anything at all"))
    }

    @Test
    fun `large commands match in linear time`() {
        val matching = "a".repeat(20_000) + "b".repeat(20_000) + "c".repeat(20_000) + "d".repeat(20_000) +
            "e".repeat(20_000)
        val notMatching = "x".repeat(100_000)

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertEquals(true, blocked("*a*b*c*d*e*", matching))
            assertEquals(true, allowed("*a*b*c*d*e*", matching))
            assertEquals(false, blocked("*x*y*", notMatching))
            assertEquals(false, allowed("*x*y*", notMatching))
        }
    }
}
