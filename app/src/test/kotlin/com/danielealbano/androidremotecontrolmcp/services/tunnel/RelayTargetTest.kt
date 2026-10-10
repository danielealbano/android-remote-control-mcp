package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RelayTarget")
class RelayTargetTest {
    private val token = "0123456789abcdef"

    private fun resolve(
        url: String,
        deviceToken: String = token,
    ) = RelayTarget.resolve(ServerConfig(relayUrl = url, relayDeviceToken = deviceToken))

    private fun failureMessage(
        url: String,
        deviceToken: String = token,
    ): String {
        val result = resolve(url, deviceToken)
        assertTrue(result.isFailure, "expected failure for '$url'")
        return result.exceptionOrNull()?.message.orEmpty()
    }

    @Test
    fun `resolve appends device path and strips trailing slash`() {
        assertEquals("wss://r.example.com/device", resolve("wss://r.example.com/").getOrThrow().deviceUrl)
    }

    @Test
    fun `resolve maps wss to https public url`() {
        assertEquals("https://r.example.com", resolve("wss://r.example.com").getOrThrow().publicUrl)
    }

    @Test
    fun `resolve maps ws to http public url with port`() {
        assertEquals("http://127.0.0.1:1234", resolve("ws://127.0.0.1:1234").getOrThrow().publicUrl)
    }

    @Test
    fun `resolve trims the token`() {
        assertEquals(token, resolve("wss://r.example.com", " $token ").getOrThrow().token)
    }

    @Test
    fun `resolve fails on empty url`() {
        assertEquals("Relay URL is required", failureMessage(""))
    }

    @Test
    fun `resolve fails on blank token`() {
        assertEquals("Relay device token is required", failureMessage("wss://r.example.com", "  "))
    }

    @Test
    fun `resolve fails on short token`() {
        assertTrue(failureMessage("wss://r.example.com", "0123456789abcde").contains("16"))
    }

    @Test
    fun `resolve fails on https scheme`() {
        assertTrue(failureMessage("https://r.example.com").contains("ws://"))
    }

    @Test
    fun `resolve fails without host`() {
        assertTrue(resolve("wss://").isFailure)
    }

    @Test
    fun `resolve fails on path`() {
        assertTrue(failureMessage("wss://r.example.com/base").contains("path"))
    }

    @Test
    fun `resolve fails on credentials query or fragment`() {
        assertTrue(resolve("wss://u:p@r.example.com").isFailure)
        assertTrue(resolve("wss://r.example.com/?a=1").isFailure)
        assertTrue(resolve("wss://r.example.com/#f").isFailure)
    }
}
