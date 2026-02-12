package com.danielealbano.androidremotecontrolmcp.services.screencapture

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ScreenCaptureService].
 *
 * Since ScreenCaptureService extends android.app.Service, direct instantiation
 * in JVM tests relies on `unitTests.isReturnDefaultValues = true` in build.gradle.
 * These tests use reflection to inject mocked dependencies and verify the service's
 * logical contract.
 *
 * Integration tests (Plan 10) will cover the full service lifecycle on a real
 * Android environment.
 */
@DisplayName("ScreenCaptureService")
class ScreenCaptureServiceTest {
    private lateinit var service: ScreenCaptureService
    private lateinit var mockMediaProjectionHelper: MediaProjectionHelper

    @BeforeEach
    fun setUp() {
        service = ScreenCaptureService()
        mockMediaProjectionHelper = mockk<MediaProjectionHelper>(relaxed = true)
        injectField("mediaProjectionHelper", mockMediaProjectionHelper)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Helper to set the injected field via reflection.
     * Since the service is not started via Hilt in unit tests, we inject manually.
     */
    private fun injectField(
        fieldName: String,
        value: Any,
    ) {
        val field = ScreenCaptureService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(service, value)
    }

    @Nested
    @DisplayName("isMediaProjectionActive")
    inner class IsMediaProjectionActiveTests {
        @Test
        @DisplayName("returns false when MediaProjection is not set up")
        fun `returns false when not set up`() {
            // Arrange
            every { mockMediaProjectionHelper.isProjectionActive() } returns false

            // Act
            val result = service.isMediaProjectionActive()

            // Assert
            assertFalse(result)
        }

        @Test
        @DisplayName("returns true when MediaProjection is set up")
        fun `returns true when set up`() {
            // Arrange
            every { mockMediaProjectionHelper.isProjectionActive() } returns true

            // Act
            val result = service.isMediaProjectionActive()

            // Assert
            assertTrue(result)
        }
    }

    @Nested
    @DisplayName("captureScreenshot")
    inner class CaptureScreenshotTests {
        @Test
        @DisplayName("returns failure when MediaProjection is not active")
        fun `returns failure when projection not active`() =
            runTest {
                // Arrange
                every { mockMediaProjectionHelper.isProjectionActive() } returns false

                // Act
                val result = service.captureScreenshot()

                // Assert
                assertTrue(result.isFailure)
                val exception = result.exceptionOrNull()
                assertNotNull(exception)
                assertTrue(exception is IllegalStateException)
                assertTrue(
                    exception!!.message!!.contains("MediaProjection is not active"),
                    "Error message should indicate projection is not active, got: ${exception.message}",
                )
            }

        @Test
        @DisplayName("failure message includes guidance to grant permission")
        fun `failure message includes guidance`() =
            runTest {
                // Arrange
                every { mockMediaProjectionHelper.isProjectionActive() } returns false

                // Act
                val result = service.captureScreenshot()

                // Assert
                val message = result.exceptionOrNull()?.message ?: ""
                assertTrue(
                    message.contains("permission", ignoreCase = true),
                    "Error message should mention permission, got: $message",
                )
            }
    }
}
