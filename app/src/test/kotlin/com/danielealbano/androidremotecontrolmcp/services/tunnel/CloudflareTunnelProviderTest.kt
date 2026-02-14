package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("CloudflareTunnelProvider")
class CloudflareTunnelProviderTest {
    private val mockBinaryResolver = mockk<CloudflaredBinaryResolver>()

    private fun createProvider(): CloudflareTunnelProvider = CloudflareTunnelProvider(mockBinaryResolver)

    @Nested
    @DisplayName("start")
    inner class Start {
        @Test
        fun `start with missing binary sets error status`() =
            runTest {
                every { mockBinaryResolver.resolve() } returns null
                val provider = createProvider()

                provider.start(8080, ServerConfig())
                advanceUntilIdle()

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertEquals(
                    "cloudflared binary not found",
                    (status as TunnelStatus.Error).message,
                )
            }

        @Test
        fun `start when already running throws IllegalStateException`() =
            runTest {
                val tempScript = File.createTempFile("fake-cloudflared", ".sh")
                try {
                    tempScript.writeText("#!/bin/sh\nsleep 60\n")
                    tempScript.setExecutable(true)
                    every { mockBinaryResolver.resolve() } returns tempScript.absolutePath

                    val provider = createProvider()
                    provider.start(8080, ServerConfig())

                    val ex =
                        assertThrows<IllegalStateException> {
                            provider.start(8080, ServerConfig())
                        }
                    assertEquals("Tunnel is already running", ex.message)

                    provider.stop()
                } finally {
                    tempScript.delete()
                }
            }
    }

    @Nested
    @DisplayName("stop")
    inner class Stop {
        @Test
        fun `stop when not running is no-op`() =
            runTest {
                val provider = createProvider()

                // Should not throw
                provider.stop()

                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }
    }

    @Nested
    @DisplayName("status")
    inner class Status {
        @Test
        fun `initial status is Disconnected`() {
            val provider = createProvider()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }
    }

    @Nested
    @DisplayName("URL regex")
    inner class UrlRegex {
        @Test
        fun `matches valid trycloudflare URL`() {
            val url = "https://random-words-here.trycloudflare.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertTrue(match != null)
            assertEquals(url, match!!.value)
        }

        @Test
        fun `matches URL with hyphens and numbers`() {
            val url = "https://my-tunnel-123-abc.trycloudflare.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertTrue(match != null)
            assertEquals(url, match!!.value)
        }

        @Test
        fun `matches URL embedded in JSON line`() {
            val json =
                """{"level":"info","time":"2026-02-13T12:00:00Z",""" +
                    """"msg":"https://abc-def.trycloudflare.com registered"}"""
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(json)
            assertTrue(match != null)
            assertEquals("https://abc-def.trycloudflare.com", match!!.value)
        }

        @Test
        fun `does not match non-trycloudflare URL`() {
            val url = "https://example.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertFalse(match != null)
        }

        @Test
        fun `does not match HTTP URL`() {
            val url = "http://random-words.trycloudflare.com"
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertFalse(match != null)
        }

        @Test
        fun `does not match URL with extra subdomain levels`() {
            val url = "https://sub.domain.trycloudflare.com"
            // The regex expects "https://" followed by a single label (no dots)
            // before .trycloudflare.com. "sub.domain" contains a dot so it
            // cannot be matched by [-a-zA-Z0-9]+.
            val match = CloudflareTunnelProvider.TUNNEL_URL_REGEX.find(url)
            assertFalse(match != null)
        }
    }

    @Nested
    @DisplayName("start with nonexistent binary path")
    inner class StartWithNonexistentBinaryPath {
        @Test
        fun `start with binary path to nonexistent file sets error status`() =
            runTest {
                every { mockBinaryResolver.resolve() } returns "/tmp/nonexistent-cloudflared-binary"
                val provider = createProvider()

                provider.start(8080, ServerConfig())
                advanceUntilIdle()

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue(
                    (status as TunnelStatus.Error).message.contains("Failed to start cloudflared"),
                )
            }
    }
}
