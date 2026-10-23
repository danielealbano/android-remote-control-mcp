package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.Build
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TypeInputControllerImplTest {
    private val inputMethodService = mockk<McpInputMethodService>()
    private val inputConnection = mockk<InputConnection>()

    @BeforeEach
    fun setUp() {
        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "Classic IME delegation test requires the JVM Android stubs to report an API below 33"
        }
        mockkObject(McpInputMethodService.Companion)
        every { McpInputMethodService.instance } returns inputMethodService
        every { inputMethodService.currentConnection() } returns inputConnection
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(McpInputMethodService.Companion)
    }

    @Test
    fun `classic IME operations delegate to current input connection below API 33`() {
        every { inputConnection.commitText("hello", 1) } returns true
        every { inputConnection.setSelection(2, 4) } returns false
        every { inputConnection.deleteSurroundingText(3, 5) } returns true

        val controller = TypeInputControllerImpl()

        assertTrue(controller.isReady())
        assertTrue(controller.commitText("hello", 1))
        assertFalse(controller.setSelection(2, 4))
        assertTrue(controller.deleteSurroundingText(3, 5))
        verify(exactly = 4) { inputMethodService.currentConnection() }
        verify(exactly = 1) { inputConnection.commitText("hello", 1) }
        verify(exactly = 1) { inputConnection.setSelection(2, 4) }
        verify(exactly = 1) { inputConnection.deleteSurroundingText(3, 5) }
    }
}
