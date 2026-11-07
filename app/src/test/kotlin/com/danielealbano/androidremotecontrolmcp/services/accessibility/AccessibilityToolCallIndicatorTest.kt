package com.danielealbano.androidremotecontrolmcp.services.accessibility

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccessibilityToolCallIndicatorTest {
    @BeforeEach
    fun setUp() {
        mockkObject(McpAccessibilityService.Companion)
        every { McpAccessibilityService.showToolCallIndicator(any()) } returns Unit
        every { McpAccessibilityService.hideToolCallIndicator() } returns Unit
    }

    @AfterEach
    fun tearDown() = unmockkObject(McpAccessibilityService.Companion)

    @Test
    fun `visual indicator shows while enabled and hides on completion`() {
        val indicator = AccessibilityToolCallIndicator { it() }
        indicator.onToolCallStarted("read")
        verify(exactly = 1) { McpAccessibilityService.showToolCallIndicator("read") }
        indicator.onToolCallFinished("read")
        verify(exactly = 1) { McpAccessibilityService.hideToolCallIndicator() }
    }

    @Test
    fun `close rejects a queued start before it can show an overlay`() {
        val pending = mutableListOf<() -> Unit>()
        val indicator = AccessibilityToolCallIndicator { pending += it }
        indicator.onToolCallStarted("queued")
        indicator.close()
        pending.forEach { it() }
        verify(exactly = 0) { McpAccessibilityService.showToolCallIndicator(any()) }
        verify(exactly = 1) { McpAccessibilityService.hideToolCallIndicator() }
    }

    @Test
    fun `disabled indicator never shows and closing rejects late starts`() {
        val indicator = AccessibilityToolCallIndicator { it() }
        indicator.setEnabled(false)
        indicator.onToolCallStarted("read")
        indicator.setEnabled(true)
        indicator.close()
        indicator.onToolCallStarted("late")
        verify(exactly = 0) { McpAccessibilityService.showToolCallIndicator(any()) }
    }
}
