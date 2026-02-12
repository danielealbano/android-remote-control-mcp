package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("McpToolUtils")
class McpToolUtilsTest {
    // ─────────────────────────────────────────────────────────────────────
    // requireFloat
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireFloat")
    inner class RequireFloatTests {
        @Test
        @DisplayName("returns float for integer value")
        fun returnsFloatForIntegerValue() {
            val params = buildJsonObject { put("x", 500) }
            assertEquals(500f, McpToolUtils.requireFloat(params, "x"))
        }

        @Test
        @DisplayName("returns float for floating-point value")
        fun returnsFloatForFloatingPointValue() {
            val params = buildJsonObject { put("x", 500.5) }
            assertEquals(500.5f, McpToolUtils.requireFloat(params, "x"))
        }

        @Test
        @DisplayName("rejects string-encoded number")
        fun rejectsStringEncodedNumber() {
            val params = buildJsonObject { put("x", "500") }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireFloat(params, "x")
                }
            assertTrue(exception.message!!.contains("got string"))
        }

        @Test
        @DisplayName("rejects missing parameter")
        fun rejectsMissingParameter() {
            val params = buildJsonObject { put("y", 100) }

            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireFloat(params, "x")
            }
        }

        @Test
        @DisplayName("rejects null params")
        fun rejectsNullParams() {
            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireFloat(null, "x")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // optionalFloat
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("optionalFloat")
    inner class OptionalFloatTests {
        @Test
        @DisplayName("returns default when parameter is absent")
        fun returnsDefaultWhenAbsent() {
            val params = buildJsonObject {}
            assertEquals(42f, McpToolUtils.optionalFloat(params, "x", 42f))
        }

        @Test
        @DisplayName("returns parsed value when present")
        fun returnsParsedValueWhenPresent() {
            val params = buildJsonObject { put("x", 100.5) }
            assertEquals(100.5f, McpToolUtils.optionalFloat(params, "x", 42f))
        }

        @Test
        @DisplayName("rejects string-encoded number")
        fun rejectsStringEncodedNumber() {
            val params = buildJsonObject { put("x", "100") }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.optionalFloat(params, "x", 42f)
                }
            assertTrue(exception.message!!.contains("got string"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // optionalLong
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("optionalLong")
    inner class OptionalLongTests {
        @Test
        @DisplayName("returns default when parameter is absent")
        fun returnsDefaultWhenAbsent() {
            val params = buildJsonObject {}
            assertEquals(300L, McpToolUtils.optionalLong(params, "duration", 300L))
        }

        @Test
        @DisplayName("returns parsed value for integer")
        fun returnsParsedValueForInteger() {
            val params = buildJsonObject { put("duration", 500) }
            assertEquals(500L, McpToolUtils.optionalLong(params, "duration", 300L))
        }

        @Test
        @DisplayName("accepts integer-equivalent float (1.0 as 1)")
        fun acceptsIntegerEquivalentFloat() {
            val params = buildJsonObject { put("duration", 500.0) }
            assertEquals(500L, McpToolUtils.optionalLong(params, "duration", 300L))
        }

        @Test
        @DisplayName("rejects fractional value")
        fun rejectsFractionalValue() {
            val params = buildJsonObject { put("duration", 1.5) }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.optionalLong(params, "duration", 300L)
                }
            assertTrue(exception.message!!.contains("integer"))
        }

        @Test
        @DisplayName("rejects string-encoded number")
        fun rejectsStringEncodedNumber() {
            val params = buildJsonObject { put("duration", "500") }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.optionalLong(params, "duration", 300L)
                }
            assertTrue(exception.message!!.contains("got string"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // requireString
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireString")
    inner class RequireStringTests {
        @Test
        @DisplayName("returns string for valid string parameter")
        fun returnsStringForValidParameter() {
            val params = buildJsonObject { put("direction", "up") }
            assertEquals("up", McpToolUtils.requireString(params, "direction"))
        }

        @Test
        @DisplayName("rejects numeric primitive as string")
        fun rejectsNumericPrimitiveAsString() {
            val params = buildJsonObject { put("direction", 123) }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireString(params, "direction")
                }
            assertTrue(exception.message!!.contains("must be a string"))
        }

        @Test
        @DisplayName("rejects boolean primitive as string")
        fun rejectsBooleanPrimitiveAsString() {
            val params = buildJsonObject { put("direction", true) }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireString(params, "direction")
                }
            assertTrue(exception.message!!.contains("must be a string"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // optionalString
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("optionalString")
    inner class OptionalStringTests {
        @Test
        @DisplayName("returns default when parameter is absent")
        fun returnsDefaultWhenAbsent() {
            val params = buildJsonObject {}
            assertEquals("medium", McpToolUtils.optionalString(params, "amount", "medium"))
        }

        @Test
        @DisplayName("returns parsed value when present")
        fun returnsParsedValueWhenPresent() {
            val params = buildJsonObject { put("amount", "large") }
            assertEquals("large", McpToolUtils.optionalString(params, "amount", "medium"))
        }

        @Test
        @DisplayName("rejects numeric primitive as string")
        fun rejectsNumericPrimitiveAsString() {
            val params = buildJsonObject { put("amount", 42) }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.optionalString(params, "amount", "medium")
                }
            assertTrue(exception.message!!.contains("must be a string"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // validateNonNegative
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateNonNegative")
    inner class ValidateNonNegativeTests {
        @Test
        @DisplayName("accepts zero")
        fun acceptsZero() {
            McpToolUtils.validateNonNegative(0f, "x")
        }

        @Test
        @DisplayName("accepts positive value")
        fun acceptsPositiveValue() {
            McpToolUtils.validateNonNegative(100f, "x")
        }

        @Test
        @DisplayName("rejects negative value")
        fun rejectsNegativeValue() {
            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.validateNonNegative(-1f, "x")
                }
            assertTrue(exception.message!!.contains(">= 0"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // validatePositiveRange
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validatePositiveRange")
    inner class ValidatePositiveRangeTests {
        @Test
        @DisplayName("accepts value within range")
        fun acceptsValueWithinRange() {
            McpToolUtils.validatePositiveRange(500L, "duration", 60000L)
        }

        @Test
        @DisplayName("accepts max value")
        fun acceptsMaxValue() {
            McpToolUtils.validatePositiveRange(60000L, "duration", 60000L)
        }

        @Test
        @DisplayName("rejects zero")
        fun rejectsZero() {
            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.validatePositiveRange(0L, "duration", 60000L)
            }
        }

        @Test
        @DisplayName("rejects value exceeding max")
        fun rejectsValueExceedingMax() {
            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.validatePositiveRange(60001L, "duration", 60000L)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // handleActionResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleActionResult")
    inner class HandleActionResultTests {
        @Test
        @DisplayName("returns text content on success")
        fun returnsTextContentOnSuccess() {
            val result = McpToolUtils.handleActionResult(Result.success(Unit), "Action done")
            val text =
                result.toString()
            assertTrue(text.contains("Action done"))
        }

        @Test
        @DisplayName("throws PermissionDenied for service not available")
        fun throwsPermissionDeniedForServiceNotAvailable() {
            val result = Result.failure<Unit>(IllegalStateException("Service is not available"))

            assertThrows<McpToolException.PermissionDenied> {
                McpToolUtils.handleActionResult(result, "Action done")
            }
        }

        @Test
        @DisplayName("throws ActionFailed for other errors")
        fun throwsActionFailedForOtherErrors() {
            val result = Result.failure<Unit>(RuntimeException("Something broke"))

            val exception =
                assertThrows<McpToolException.ActionFailed> {
                    McpToolUtils.handleActionResult(result, "Action done")
                }
            assertTrue(exception.message!!.contains("Something broke"))
        }
    }
}
