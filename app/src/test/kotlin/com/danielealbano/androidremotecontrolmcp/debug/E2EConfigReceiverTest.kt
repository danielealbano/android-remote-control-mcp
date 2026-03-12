package com.danielealbano.androidremotecontrolmcp.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [E2EConfigReceiver] constants.
 *
 * The receiver itself uses `@AndroidEntryPoint` and `scope.launch` making
 * direct unit testing complex without a full Hilt test environment.
 * These tests verify that the companion constants are defined correctly.
 */
@DisplayName("E2EConfigReceiver")
class E2EConfigReceiverTest {
    @Test
    fun `ACTION_E2E_CONFIGURE constant is defined`() {
        assertEquals(
            "com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE",
            E2EConfigReceiver.ACTION_E2E_CONFIGURE,
        )
    }

    @Test
    fun `ACTION_E2E_START_SERVER constant is defined`() {
        assertEquals(
            "com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER",
            E2EConfigReceiver.ACTION_E2E_START_SERVER,
        )
    }
}
