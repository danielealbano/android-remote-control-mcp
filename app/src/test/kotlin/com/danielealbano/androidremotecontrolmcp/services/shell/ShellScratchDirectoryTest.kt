package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

@EnabledOnOs(OS.LINUX)
@DisplayName("ShellScratchDirectoryImpl")
class ShellScratchDirectoryTest {
    @TempDir
    lateinit var cacheTempDir: File

    @TempDir
    lateinit var outsideDir: File

    private lateinit var scratch: ShellScratchDirectoryImpl
    private lateinit var shell: File
    private lateinit var keep: File

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        scratch = ShellScratchDirectoryImpl({ cacheTempDir }, "/bin/sh")
        shell = File(cacheTempDir, "shell")
        keep = File(outsideDir, "keep.txt").apply { writeText("keep") }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `path is the shell folder in the external cache`() {
        assertEquals(shell, scratch.path())
    }

    @Test
    fun `reset wipes and recreates`() {
        File(shell, "sub").mkdirs()
        File(shell, "a.txt").writeText("a")
        File(shell, "sub/b.txt").writeText("b")

        val result = scratch.reset()

        assertEquals(shell, result)
        assertTrue(shell.isDirectory)
        assertTrue(shell.list().orEmpty().isEmpty())
    }

    @Test
    fun `the folder is open to the group`() {
        scratch.reset()

        val permissions = Files.getPosixFilePermissions(shell.toPath())

        assertTrue(
            permissions.containsAll(
                setOf(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                ),
            ),
            permissions.toString(),
        )
        assertTrue(
            permissions.none {
                it in
                    setOf(
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE,
                    )
            },
            permissions.toString(),
        )
    }

    @Test
    fun `undeletable entries are skipped, the rest is deleted`() {
        val locked = File(shell, "locked").apply { mkdirs() }
        File(locked, "inside.txt").writeText("x")
        File(shell, "a.txt").writeText("a")
        File(shell, "b.txt").writeText("b")
        Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("---------"))
        try {
            scratch.reset()

            assertFalse(File(shell, "a.txt").exists())
            assertFalse(File(shell, "b.txt").exists())
            assertTrue(locked.exists())
            verify(atLeast = 1) { Log.w(any<String>(), any<String>(), any()) }
        } finally {
            Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `reset deletes links without following them`() {
        shell.mkdirs()
        val link = File(shell, "link")
        Files.createSymbolicLink(link.toPath(), outsideDir.toPath())

        scratch.reset()

        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(keep.exists())
    }

    @Test
    fun `a linked scratch folder is replaced by a real one`() {
        Files.createSymbolicLink(shell.toPath(), outsideDir.toPath())

        scratch.reset()

        assertFalse(Files.isSymbolicLink(shell.toPath()))
        assertTrue(shell.isDirectory)
        assertTrue(shell.list().orEmpty().isEmpty())
        assertTrue(keep.exists())
    }

    @Test
    fun `no external storage`() {
        val missing = ShellScratchDirectoryImpl({ null }, "/bin/sh")

        assertNull(missing.path())
        assertNull(missing.reset())
    }
}
