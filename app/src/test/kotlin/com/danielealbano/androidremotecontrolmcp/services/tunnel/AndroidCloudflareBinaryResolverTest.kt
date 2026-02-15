package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("AndroidCloudflareBinaryResolver")
class AndroidCloudflareBinaryResolverTest {
    private val mockContext = mockk<Context>()
    private val mockApplicationInfo = ApplicationInfo()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockApplicationInfo.nativeLibraryDir = tempDir.absolutePath
        every { mockContext.applicationInfo } returns mockApplicationInfo
    }

    private fun createResolver(): AndroidCloudflareBinaryResolver = AndroidCloudflareBinaryResolver(mockContext)

    @Nested
    @DisplayName("resolve")
    inner class Resolve {
        @Test
        fun `returns path when binary exists and is executable`() {
            val binaryFile = File(tempDir, "libcloudflared.so")
            binaryFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            binaryFile.setExecutable(true, true)

            val resolver = createResolver()
            val result = resolver.resolve()

            assertEquals(binaryFile.absolutePath, result)
        }

        @Test
        fun `returns null when binary does not exist`() {
            val resolver = createResolver()
            val result = resolver.resolve()

            assertNull(result)
        }

        @Test
        fun `returns null when binary exists but is not executable`() {
            val binaryFile = File(tempDir, "libcloudflared.so")
            binaryFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            binaryFile.setExecutable(false, false)

            val resolver = createResolver()
            val result = resolver.resolve()

            assertNull(result)
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun mockAndroidLog() {
            mockkStatic(Log::class)
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }

        @JvmStatic
        @AfterAll
        fun unmockAndroidLog() {
            unmockkStatic(Log::class)
        }
    }
}
