package com.danielealbano.androidremotecontrolmcp.services.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [AdbServiceTrampolineActivity] constants.
 *
 * The Activity itself is a thin lifecycle wrapper (reads an intent extra,
 * starts/stops [McpServerService], finishes immediately) and is verified
 * via on-device testing. These tests ensure the ADB command contract
 * (extra key and action values) remains stable.
 */
@DisplayName("AdbServiceTrampolineActivity")
class AdbServiceTrampolineActivityTest {
    @Test
    fun `EXTRA_ACTION has expected value`() {
        assertEquals("action", AdbServiceTrampolineActivity.EXTRA_ACTION)
    }

    @Test
    fun `ACTION_VALUE_START has expected value`() {
        assertEquals("start", AdbServiceTrampolineActivity.ACTION_VALUE_START)
    }

    @Test
    fun `ACTION_VALUE_STOP has expected value`() {
        assertEquals("stop", AdbServiceTrampolineActivity.ACTION_VALUE_STOP)
    }
}
