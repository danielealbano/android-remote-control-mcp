package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

@DisplayName("System Action Tools")
class SystemActionToolsTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockActionExecutor: ActionExecutor

    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockActionExecutor = mockk<ActionExecutor>()
        every { mockAccessibilityServiceProvider.isReady() } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Verifies the standard text content response format.
     */
    private fun assertTextContentResponse(
        result: CallToolResult,
        containsText: String,
    ) {
        assertEquals(1, result.content.size)
        val textContent = result.content[0] as TextContent
        assertNotNull(textContent.text)
        assertTrue(
            textContent.text!!.contains(containsText),
            "Expected text to contain '$containsText' but was '${textContent.text}'",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // press_back
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PressBackHandler")
    inner class PressBackTests {
        private lateinit var handler: PressBackHandler

        @BeforeEach
        fun setUp() {
            handler = PressBackHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.pressBack and returns confirmation")
        fun callsPressBackAndReturnsConfirmation() =
            runTest {
                // Arrange
                coEvery { mockActionExecutor.pressBack() } returns Result.success(Unit)

                // Act
                val result = handler.execute(null)

                // Assert
                coVerify(exactly = 1) { mockActionExecutor.pressBack() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
            }

        @Test
        @DisplayName("throws error -32003 when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                // Arrange
                coEvery { mockActionExecutor.pressBack() } returns
                    Result.failure(
                        RuntimeException("Global action failed"),
                    )

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Global action failed"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // press_home
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PressHomeHandler")
    inner class PressHomeTests {
        private lateinit var handler: PressHomeHandler

        @BeforeEach
        fun setUp() {
            handler = PressHomeHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.pressHome and returns confirmation")
        fun callsPressHomeAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.pressHome() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.pressHome() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws error -32003 when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.pressHome() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // press_recents
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PressRecentsHandler")
    inner class PressRecentsTests {
        private lateinit var handler: PressRecentsHandler

        @BeforeEach
        fun setUp() {
            handler = PressRecentsHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.pressRecents and returns confirmation")
        fun callsPressRecentsAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.pressRecents() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.pressRecents() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws error -32003 when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.pressRecents() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // open_notifications
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OpenNotificationsHandler")
    inner class OpenNotificationsTests {
        private lateinit var handler: OpenNotificationsHandler

        @BeforeEach
        fun setUp() {
            handler = OpenNotificationsHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.openNotifications and returns confirmation")
        fun callsOpenNotificationsAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.openNotifications() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.openNotifications() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws error -32003 when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.openNotifications() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // open_quick_settings
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OpenQuickSettingsHandler")
    inner class OpenQuickSettingsTests {
        private lateinit var handler: OpenQuickSettingsHandler

        @BeforeEach
        fun setUp() {
            handler = OpenQuickSettingsHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.openQuickSettings and returns confirmation")
        fun callsOpenQuickSettingsAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.openQuickSettings() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.openQuickSettings() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws error -32003 when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.openQuickSettings() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                val exception = assertThrows<McpToolException> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // get_device_logs
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetDeviceLogsHandler")
    inner class GetDeviceLogsTests {
        private lateinit var handler: GetDeviceLogsHandler
        private lateinit var mockRuntime: Runtime
        private lateinit var mockProcess: Process

        @BeforeEach
        fun setUp() {
            handler = GetDeviceLogsHandler()

            // Mock Runtime.getRuntime().exec() since logcat is not available on host
            mockProcess = mockk<Process>()
            mockRuntime = mockk<Runtime>()
            mockkStatic(Runtime::class)
            every { Runtime.getRuntime() } returns mockRuntime
            every { mockRuntime.exec(any<Array<String>>()) } returns mockProcess
            every { mockProcess.inputStream } returns
                ByteArrayInputStream("2024-01-15 10:30:00 D/TestTag: Sample log line\n".toByteArray())
            every { mockProcess.errorStream } returns ByteArrayInputStream(ByteArray(0))
            every { mockProcess.waitFor() } returns 0
        }

        @Test
        @DisplayName("returns logs with default parameters")
        fun returnsLogsWithDefaultParameters() =
            runTest {
                // Act
                val result = handler.execute(null)

                // Assert
                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertNotNull(textContent.text)
                assertTrue(textContent.text!!.contains("logs"))
                assertTrue(textContent.text!!.contains("line_count"))
                assertTrue(textContent.text!!.contains("truncated"))
            }

        @Test
        @DisplayName("returns logs with custom last_lines")
        fun returnsLogsWithCustomLastLines() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("last_lines", JsonPrimitive(50)) }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertNotNull(textContent.text)
            }

        @Test
        @DisplayName("throws error -32602 when last_lines is below minimum (0)")
        fun throwsErrorWhenLastLinesBelowMinimum() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("last_lines", JsonPrimitive(0)) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("last_lines"))
            }

        @Test
        @DisplayName("throws error -32602 when last_lines exceeds maximum (1001)")
        fun throwsErrorWhenLastLinesExceedsMaximum() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("last_lines", JsonPrimitive(1001)) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("last_lines"))
            }

        @Test
        @DisplayName("throws error -32602 when last_lines is non-integer")
        fun throwsErrorWhenLastLinesNonInteger() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("last_lines", JsonPrimitive("abc")) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("integer"))
            }

        @Test
        @DisplayName("throws error -32602 for invalid log level")
        fun throwsErrorForInvalidLogLevel() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("level", JsonPrimitive("X")) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("level"))
            }

        @Test
        @DisplayName("accepts valid log levels")
        fun acceptsValidLogLevels() =
            runTest {
                // Verify each valid level does not throw InvalidParams
                for (level in listOf("V", "D", "I", "W", "E", "F")) {
                    val params = buildJsonObject { put("level", JsonPrimitive(level)) }
                    val result = handler.execute(params)
                    assertTrue(result.content.isNotEmpty(), "Expected content for level $level")
                }
            }

        @Test
        @DisplayName("accepts optional tag parameter")
        fun acceptsOptionalTagParameter() =
            runTest {
                // Arrange
                val params =
                    buildJsonObject {
                        put("tag", JsonPrimitive("MCP:ServerService"))
                        put("level", JsonPrimitive("W"))
                    }

                // Act
                val result = handler.execute(params)

                // Assert
                assertTrue(result.content.isNotEmpty())
            }

        @Test
        @DisplayName("accepts optional since parameter")
        fun acceptsOptionalSinceParameter() =
            runTest {
                // Arrange
                val params =
                    buildJsonObject {
                        put("since", JsonPrimitive("2024-01-15T10:30:00"))
                    }

                // Act
                val result = handler.execute(params)

                // Assert
                assertTrue(result.content.isNotEmpty())
            }

        @Test
        @DisplayName("filters logs by until timestamp")
        fun filtersLogsByUntilTimestamp() =
            runTest {
                // Arrange - two log lines, one before and one after the until time
                val logOutput =
                    "01-15 10:00:00.000 D/Tag: Before line\n" +
                        "01-15 11:00:00.000 D/Tag: After line\n"
                every { mockProcess.inputStream } returns
                    ByteArrayInputStream(logOutput.toByteArray())
                val params =
                    buildJsonObject {
                        put("since", JsonPrimitive("2024-01-15T09:00:00"))
                        put("until", JsonPrimitive("2024-01-15T10:30:00"))
                    }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertNotNull(textContent.text)
                assertTrue(textContent.text!!.contains("Before line"))
                assertTrue(!textContent.text!!.contains("After line"))
            }

        @Test
        @DisplayName("resolves package_name to PID for logcat filtering")
        fun resolvesPackageNameToPid() =
            runTest {
                // Arrange - first exec call resolves PID, second call is logcat
                val pidProcess = mockk<Process>()
                every { pidProcess.inputStream } returns
                    ByteArrayInputStream("12345\n".toByteArray())
                every { pidProcess.waitFor() } returns 0

                val logProcess = mockk<Process>()
                every { logProcess.inputStream } returns
                    ByteArrayInputStream("01-15 10:00:00.000 D/Tag: App log\n".toByteArray())
                every { logProcess.errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { logProcess.waitFor() } returns 0

                // First exec call = pidof, second = logcat
                every { mockRuntime.exec(any<Array<String>>()) } returnsMany
                    listOf(pidProcess, logProcess)

                val params =
                    buildJsonObject {
                        put("package_name", JsonPrimitive("com.example.app"))
                    }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertNotNull(textContent.text)
                assertTrue(textContent.text!!.contains("App log"))
            }

        @Test
        @DisplayName("detects truncation correctly with extra line")
        fun detectsTruncationCorrectly() =
            runTest {
                // Arrange - provide exactly 2 lines for last_lines=1 (1+1 requested)
                val logOutput =
                    "01-15 10:00:00.000 D/Tag: Line 1\n" +
                        "01-15 10:01:00.000 D/Tag: Line 2\n"
                every { mockProcess.inputStream } returns
                    ByteArrayInputStream(logOutput.toByteArray())
                val params = buildJsonObject { put("last_lines", JsonPrimitive(1)) }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertNotNull(textContent.text)
                assertTrue(textContent.text!!.contains("\"truncated\":true"))
                assertTrue(textContent.text!!.contains("\"line_count\":1"))
            }
    }
}
