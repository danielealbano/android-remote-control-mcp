package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private lateinit var mockService: McpAccessibilityService
    private lateinit var mockActionExecutor: ActionExecutor

    @BeforeEach
    fun setUp() {
        mockService = mockk<McpAccessibilityService>(relaxed = true)
        mockActionExecutor = mockk<ActionExecutor>()
        setAccessibilityServiceInstance(mockService)
    }

    @AfterEach
    fun tearDown() {
        setAccessibilityServiceInstance(null)
        unmockkAll()
    }

    private fun setAccessibilityServiceInstance(instance: McpAccessibilityService?) {
        val field = McpAccessibilityService::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, instance)
    }

    /**
     * Verifies the standard text content response format.
     */
    private fun assertTextContentResponse(
        result: JsonElement,
        containsText: String,
    ) {
        val content = result.jsonObject["content"]?.jsonArray
        assertNotNull(content)
        assertEquals(1, content!!.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        val text = content[0].jsonObject["text"]?.jsonPrimitive?.content
        assertNotNull(text)
        assertTrue(text!!.contains(containsText), "Expected text to contain '$containsText' but was '$text'")
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
            handler = PressBackHandler(mockActionExecutor)
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
                setAccessibilityServiceInstance(null)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
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
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
            handler = PressHomeHandler(mockActionExecutor)
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
                setAccessibilityServiceInstance(null)
                val exception = assertThrows<McpToolException> { handler.execute(null) }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
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
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
            handler = PressRecentsHandler(mockActionExecutor)
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
                setAccessibilityServiceInstance(null)
                val exception = assertThrows<McpToolException> { handler.execute(null) }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
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
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
            handler = OpenNotificationsHandler(mockActionExecutor)
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
                setAccessibilityServiceInstance(null)
                val exception = assertThrows<McpToolException> { handler.execute(null) }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
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
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
            handler = OpenQuickSettingsHandler(mockActionExecutor)
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
                setAccessibilityServiceInstance(null)
                val exception = assertThrows<McpToolException> { handler.execute(null) }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
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
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                assertEquals(1, content!!.size)
                assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
                val textContent = content[0].jsonObject["text"]?.jsonPrimitive?.content
                assertNotNull(textContent)
                assertTrue(textContent!!.contains("logs"))
                assertTrue(textContent.contains("line_count"))
                assertTrue(textContent.contains("truncated"))
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                assertEquals("text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
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
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
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
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
                assertTrue(exception.message!!.contains("last_lines"))
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
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
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
                    val content = result.jsonObject["content"]?.jsonArray
                    assertNotNull(content, "Expected content for level $level")
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
            }
    }
}
