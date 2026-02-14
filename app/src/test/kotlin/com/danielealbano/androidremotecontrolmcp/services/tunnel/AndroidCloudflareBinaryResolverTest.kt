package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@DisplayName("AndroidCloudflareBinaryResolver")
class AndroidCloudflareBinaryResolverTest {
    private val mockContext = mockk<Context>()
    private val mockAssetManager = mockk<AssetManager>()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        every { mockContext.assets } returns mockAssetManager
        every { mockContext.filesDir } returns tempDir
    }

    private fun createResolver(vararg abis: String): AndroidCloudflareBinaryResolver {
        val resolver = AndroidCloudflareBinaryResolver(mockContext)
        resolver.supportedAbis = arrayOf(*abis)
        return resolver
    }

    @Nested
    @DisplayName("resolve")
    inner class Resolve {
        @Test
        fun `extracts matching asset for primary ABI and returns path`() {
            val binaryContent = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
            every { mockAssetManager.list("") } returns arrayOf("cloudflared-arm64-v8a")
            every { mockAssetManager.open("cloudflared-arm64-v8a") } returns
                ByteArrayInputStream(binaryContent)

            val resolver = createResolver("arm64-v8a", "x86_64")
            val result = resolver.resolve()

            val expectedFile = File(tempDir, "cloudflared")
            assertEquals(expectedFile.absolutePath, result)
            assertTrue(expectedFile.exists())
            assertTrue(expectedFile.canExecute())
            assertEquals(binaryContent.size.toLong(), expectedFile.length())
        }

        @Test
        fun `falls back to secondary ABI when primary is not available`() {
            val binaryContent = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
            every { mockAssetManager.list("") } returns arrayOf("cloudflared-x86_64")
            every { mockAssetManager.open("cloudflared-x86_64") } returns
                ByteArrayInputStream(binaryContent)

            val resolver = createResolver("arm64-v8a", "x86_64")
            val result = resolver.resolve()

            val expectedFile = File(tempDir, "cloudflared")
            assertEquals(expectedFile.absolutePath, result)
            assertTrue(expectedFile.exists())
        }

        @Test
        fun `returns null when no matching asset exists`() {
            every { mockAssetManager.list("") } returns arrayOf("some-other-asset")

            val resolver = createResolver("arm64-v8a")
            val result = resolver.resolve()

            assertNull(result)
        }

        @Test
        fun `returns null when assets list is empty`() {
            every { mockAssetManager.list("") } returns emptyArray()

            val resolver = createResolver("arm64-v8a")
            val result = resolver.resolve()

            assertNull(result)
        }

        @Test
        fun `returns null when assets list returns null`() {
            every { mockAssetManager.list("") } returns null

            val resolver = createResolver("arm64-v8a")
            val result = resolver.resolve()

            assertNull(result)
        }

        @Test
        fun `returns null when asset extraction throws IOException`() {
            every { mockAssetManager.list("") } returns arrayOf("cloudflared-arm64-v8a")
            every { mockAssetManager.open("cloudflared-arm64-v8a") } throws
                IOException("Asset not found")

            val resolver = createResolver("arm64-v8a")
            val result = resolver.resolve()

            assertNull(result)
        }

        @Test
        fun `overwrites existing binary on each extraction`() {
            val oldContent = byteArrayOf(0x01, 0x02)
            val newContent = byteArrayOf(0x03, 0x04, 0x05, 0x06)

            val existingFile = File(tempDir, "cloudflared")
            existingFile.writeBytes(oldContent)

            every { mockAssetManager.list("") } returns arrayOf("cloudflared-arm64-v8a")
            every { mockAssetManager.open("cloudflared-arm64-v8a") } returns
                ByteArrayInputStream(newContent)

            val resolver = createResolver("arm64-v8a")
            val result = resolver.resolve()

            assertEquals(existingFile.absolutePath, result)
            assertEquals(newContent.size.toLong(), existingFile.length())
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
