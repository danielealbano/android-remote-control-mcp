package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    // requireInt
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireInt")
    inner class RequireIntTests {
        @Test
        @DisplayName("returns integer value")
        fun returnsIntegerValue() {
            val params = buildJsonObject { put("x", 42) }
            assertEquals(42, McpToolUtils.requireInt(params, "x"))
        }

        @Test
        @DisplayName("throws for missing param")
        fun throwsForMissingParam() {
            val params = buildJsonObject { put("y", 100) }

            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireInt(params, "x")
            }
        }

        @Test
        @DisplayName("throws for null param")
        fun throwsForNullParam() {
            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireInt(null, "x")
            }
        }

        @Test
        @DisplayName("throws for string-encoded number")
        fun throwsForStringEncodedNumber() {
            val params = buildJsonObject { put("x", "42") }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireInt(params, "x")
                }
            assertTrue(exception.message!!.contains("got string"))
        }

        @Test
        @DisplayName("throws for float value")
        fun throwsForFloatValue() {
            val params = buildJsonObject { put("x", 3.5) }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireInt(params, "x")
                }
            assertTrue(exception.message!!.contains("integer"))
        }

        @Test
        @DisplayName("accepts integer-equivalent float")
        fun acceptsIntegerEquivalentFloat() {
            val params = buildJsonObject { put("x", 5.0) }
            assertEquals(5, McpToolUtils.requireInt(params, "x"))
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
    // requireLong
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireLong")
    inner class RequireLongTests {
        @Test
        @DisplayName("valid integer returns Long")
        fun validIntegerReturnsLong() {
            val params = buildJsonObject { put("duration", 60000) }
            assertEquals(60000L, McpToolUtils.requireLong(params, "duration"))
        }

        @Test
        @DisplayName("missing param throws InvalidParams")
        fun missingParamThrowsInvalidParams() {
            val params = buildJsonObject { put("other", 100) }

            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireLong(params, "duration")
            }
        }

        @Test
        @DisplayName("null params throws InvalidParams")
        fun nullParamsThrowsInvalidParams() {
            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireLong(null, "duration")
            }
        }

        @Test
        @DisplayName("non-number throws InvalidParams")
        fun nonNumberThrowsInvalidParams() {
            val params = buildJsonObject { put("duration", true) }

            assertThrows<McpToolException.InvalidParams> {
                McpToolUtils.requireLong(params, "duration")
            }
        }

        @Test
        @DisplayName("decimal throws InvalidParams")
        fun decimalThrowsInvalidParams() {
            val params = buildJsonObject { put("duration", 3.5) }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireLong(params, "duration")
                }
            assertTrue(exception.message!!.contains("integer"))
        }

        @Test
        @DisplayName("rejects string-encoded number")
        fun rejectsStringEncodedNumber() {
            val params = buildJsonObject { put("duration", "60000") }

            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    McpToolUtils.requireLong(params, "duration")
                }
            assertTrue(exception.message!!.contains("got string"))
        }

        @Test
        @DisplayName("accepts integer-equivalent float")
        fun acceptsIntegerEquivalentFloat() {
            val params = buildJsonObject { put("duration", 5.0) }
            assertEquals(5L, McpToolUtils.requireLong(params, "duration"))
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
    // requireNodeIds
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireNodeIds")
    inner class RequireNodeIdsTests {
        private fun error(params: JsonObject?): String? =
            assertThrows<McpToolException.InvalidParams> { McpToolUtils.requireNodeIds(params) }.message

        @Test
        fun `returns the ids in order`() {
            val params =
                buildJsonObject {
                    putJsonArray("node_ids") {
                        add("b")
                        add("a")
                    }
                }

            assertEquals(listOf("b", "a"), McpToolUtils.requireNodeIds(params))
        }

        @Test
        fun `rejects a missing parameter`() {
            assertEquals("Missing required parameter 'node_ids'", error(null))
            assertEquals("Missing required parameter 'node_ids'", error(buildJsonObject {}))
        }

        @Test
        fun `rejects a value that is not an array`() {
            assertEquals(
                "Parameter 'node_ids' must be an array of strings",
                error(buildJsonObject { put("node_ids", "a") }),
            )
        }

        @Test
        fun `rejects an empty array`() {
            assertEquals("Parameter 'node_ids' must not be empty", error(buildJsonObject { putJsonArray("node_ids") {} }))
        }

        @Test
        fun `rejects an element that is not a string`() {
            val number =
                buildJsonObject {
                    putJsonArray("node_ids") {
                        add("a")
                        add(1)
                    }
                }
            val obj = buildJsonObject { putJsonArray("node_ids") { addJsonObject {} } }

            assertEquals("Each element in 'node_ids' must be a string", error(number))
            assertEquals("Each element in 'node_ids' must be a string", error(obj))
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
            assertEquals(1, result.content.size)
            val textContent = result.content[0] as TextContent
            assertEquals("Action done", textContent.text)
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

    // ─────────────────────────────────────────────────────────────────────
    // textResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("textResult")
    inner class TextResultTests {
        @Test
        @DisplayName("returns CallToolResult with TextContent")
        fun returnsCallToolResultWithTextContent() {
            val result = McpToolUtils.textResult("Hello world")
            assertEquals(1, result.content.size)
            val textContent = result.content[0] as TextContent
            assertEquals("Hello world", textContent.text)
        }

        @Test
        @DisplayName("returns CallToolResult with empty text")
        fun returnsCallToolResultWithEmptyText() {
            val result = McpToolUtils.textResult("")
            assertEquals(1, result.content.size)
            val textContent = result.content[0] as TextContent
            assertEquals("", textContent.text)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // imageResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("imageResult")
    inner class ImageResultTests {
        @Test
        @DisplayName("returns CallToolResult with ImageContent")
        fun returnsCallToolResultWithImageContent() {
            val result = McpToolUtils.imageResult(data = "base64data", mimeType = "image/jpeg")
            assertEquals(1, result.content.size)
            val imageContent = result.content[0] as ImageContent
            assertEquals("base64data", imageContent.data)
            assertEquals("image/jpeg", imageContent.mimeType)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // textAndImageResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("textAndImageResult")
    inner class TextAndImageResultTests {
        @Test
        @DisplayName("returns CallToolResult with TextContent and ImageContent")
        fun returnsCallToolResultWithTextAndImageContent() {
            val result = McpToolUtils.textAndImageResult("hello", "base64data", "image/jpeg")
            assertEquals(2, result.content.size)
            val text = result.content[0] as TextContent
            val image = result.content[1] as ImageContent
            assertEquals("hello", text.text)
            assertEquals("base64data", image.data)
            assertEquals("image/jpeg", image.mimeType)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildToolNamePrefix
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildToolNamePrefix")
    inner class BuildToolNamePrefixTests {
        @Test
        @DisplayName("returns android_ for empty slug")
        fun returnsAndroidPrefixForEmptySlug() {
            assertEquals("android_", McpToolUtils.buildToolNamePrefix(""))
        }

        @Test
        @DisplayName("returns android_slug_ for non-empty slug")
        fun returnsAndroidSlugPrefixForNonEmptySlug() {
            assertEquals("android_pixel7_", McpToolUtils.buildToolNamePrefix("pixel7"))
        }

        @Test
        @DisplayName("handles slug with underscores")
        fun handlesSlugWithUnderscores() {
            assertEquals("android_work_phone_", McpToolUtils.buildToolNamePrefix("work_phone"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // untrustedTextResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("untrustedTextResult")
    inner class UntrustedTextResultTests {
        @Test
        @DisplayName("prepends warning before content")
        fun prependsWarningBeforeContent() {
            val result = McpToolUtils.untrustedTextResult("some content")
            assertEquals(1, result.content.size)
            val text = (result.content[0] as TextContent).text
            assertTrue(text.startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            assertTrue(text.endsWith("some content"))
        }

        @Test
        @DisplayName("text starts with warning then newline")
        fun textStartsWithWarningThenNewline() {
            val result = McpToolUtils.untrustedTextResult("data")
            val text = (result.content[0] as TextContent).text
            assertEquals("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\ndata", text)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // untrustedTextAndImageResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("untrustedTextAndImageResult")
    inner class UntrustedTextAndImageResultTests {
        @Test
        @DisplayName("prepends warning to text and includes image")
        fun prependsWarningToTextAndIncludesImage() {
            val result = McpToolUtils.untrustedTextAndImageResult("info", "imgdata", "image/png")
            assertEquals(2, result.content.size)
            val text = result.content[0] as TextContent
            val image = result.content[1] as ImageContent
            assertTrue(text.text.startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            assertTrue(text.text.endsWith("info"))
            assertEquals("imgdata", image.data)
            assertEquals("image/png", image.mimeType)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // untrustedTextAndImagesResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("untrustedTextAndImagesResult")
    inner class UntrustedTextAndImagesResultTests {
        @Test
        fun `gives one text item with the warning first, then the images in order`() {
            val result = McpToolUtils.untrustedTextAndImagesResult("lines", listOf("one", "two"), "image/jpeg")

            assertEquals(3, result.content.size)
            assertEquals("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\nlines", (result.content[0] as TextContent).text)
            assertEquals(listOf("one", "two"), result.content.drop(1).map { (it as ImageContent).data })
            assertTrue(result.content.drop(1).all { (it as ImageContent).mimeType == "image/jpeg" })
        }

        @Test
        fun `gives the text item alone when there is no image`() {
            val result = McpToolUtils.untrustedTextAndImagesResult("lines", emptyList(), "image/jpeg")

            assertEquals(1, result.content.size)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // untrustedImageResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("untrustedImageResult")
    inner class UntrustedImageResultTests {
        @Test
        @DisplayName("adds warning as separate text content before image")
        fun addsWarningAsSeparateTextBeforeImage() {
            val result = McpToolUtils.untrustedImageResult("imgdata", "image/jpeg")
            assertEquals(2, result.content.size)
            val text = result.content[0] as TextContent
            val image = result.content[1] as ImageContent
            assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.text)
            assertEquals("imgdata", image.data)
            assertEquals("image/jpeg", image.mimeType)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // untrustedResult
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("untrustedResult")
    inner class UntrustedResultTests {
        @Test
        @DisplayName("prepends warning as first content block, preserving the rest in order")
        fun prependsWarningFirst() {
            val first = TextContent("first")
            val image = ImageContent(data = "imgdata", mimeType = "image/png")
            val result = McpToolUtils.untrustedResult(listOf(first, image))

            assertEquals(3, result.content.size)
            assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, (result.content[0] as TextContent).text)
            assertEquals("first", (result.content[1] as TextContent).text)
            val resultImage = result.content[2] as ImageContent
            assertEquals("imgdata", resultImage.data)
            assertEquals("image/png", resultImage.mimeType)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNTRUSTED_CONTENT_WARNING constant
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UNTRUSTED_CONTENT_WARNING")
    inner class UntrustedContentWarningTests {
        @Test
        @DisplayName("is not empty")
        fun isNotEmpty() {
            assertTrue(McpToolUtils.UNTRUSTED_CONTENT_WARNING.isNotBlank())
        }

        @Test
        @DisplayName("does not use note prefix")
        fun doesNotUseNotePrefix() {
            assertTrue(!McpToolUtils.UNTRUSTED_CONTENT_WARNING.lowercase().startsWith("note:"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildServerName
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildServerName")
    inner class BuildServerNameTests {
        @Test
        @DisplayName("returns default name for empty slug")
        fun returnsDefaultNameForEmptySlug() {
            assertEquals("android-remote-control-mcp", McpToolUtils.buildServerName(""))
        }

        @Test
        @DisplayName("includes slug in server name")
        fun includesSlugInServerName() {
            assertEquals("android-remote-control-mcp-pixel7", McpToolUtils.buildServerName("pixel7"))
        }

        @Test
        @DisplayName("handles slug with underscores")
        fun handlesSlugWithUnderscores() {
            assertEquals("android-remote-control-mcp-work_phone", McpToolUtils.buildServerName("work_phone"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildNodeJson
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildNodeJson")
    inner class BuildNodeJsonTests {
        private fun element(password: Boolean) =
            ElementInfo(
                id = "node_pwd",
                className = "android.widget.EditText",
                bounds = BoundsData(0, 0, 100, 50),
                editable = true,
                password = password,
            )

        @Test
        @DisplayName("reports password true for a password field")
        fun reportsPasswordTrueForPasswordField() {
            val json = McpToolUtils.buildNodeJson(element(password = true))
            assertEquals(true, json["password"]?.jsonPrimitive?.boolean)
        }

        @Test
        @DisplayName("reports password false for a regular field")
        fun reportsPasswordFalseForRegularField() {
            val json = McpToolUtils.buildNodeJson(element(password = false))
            assertEquals(false, json["password"]?.jsonPrimitive?.boolean)
        }

        @Test
        @DisplayName("lists the action labels in order")
        fun listsActionLabelsInOrder() {
            val withActions = element(password = false).copy(actions = listOf("Upvote", "Downvote"))
            val json = McpToolUtils.buildNodeJson(withActions)
            assertEquals(listOf("Upvote", "Downvote"), json["actions"]?.jsonArray?.map { it.jsonPrimitive.content })
        }

        @Test
        @DisplayName("reports an empty actions array for a node without actions")
        fun reportsEmptyActionsArray() {
            val json = McpToolUtils.buildNodeJson(element(password = false))
            assertEquals(0, json["actions"]?.jsonArray?.size)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // redactElements
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("redactElements")
    inner class RedactElementsTests {
        private val windows =
            listOf(
                WindowData(
                    windowId = 1,
                    windowType = "APPLICATION",
                    tree = AccessibilityNodeData(id = "node_root", bounds = BoundsData(0, 0, 1, 1)),
                ),
            )

        private fun element(
            id: String,
            text: String?,
            actions: List<String>,
        ) = ElementInfo(
            id = id,
            text = text,
            contentDescription = "desc $id",
            bounds = BoundsData(0, 0, 1, 1),
            actions = actions,
        )

        @Test
        @DisplayName("redacts text, description and labels in one batch, each in its own slot")
        fun redactsEveryFieldInOneBatch() =
            runTest {
                val gate = mockk<PrivacyToolGate>()
                val batch = slot<List<Pair<String?, String>>>()
                coEvery { gate.nodeTexts(windows, capture(batch)) } answers {
                    secondArg<List<Pair<String?, String>>>().map { (text, _) -> text?.let { "R($it)" } }
                }
                val elements =
                    listOf(
                        element("node_a", "Alice", listOf("Call Alice", "Mail Alice")),
                        element("node_b", null, emptyList()),
                        element("node_c", "Carol", listOf("Upvote")),
                    )

                val redacted = McpToolUtils.redactElements(gate, windows, elements)

                coVerify(exactly = 1) { gate.nodeTexts(windows, any()) }
                assertEquals(
                    listOf(
                        "Alice" to "node_a",
                        "desc node_a" to "node_a",
                        "Call Alice" to "node_a",
                        "Mail Alice" to "node_a",
                        null to "node_b",
                        "desc node_b" to "node_b",
                        "Carol" to "node_c",
                        "desc node_c" to "node_c",
                        "Upvote" to "node_c",
                    ),
                    batch.captured,
                )
                assertEquals(
                    listOf(
                        element("node_a", "R(Alice)", listOf("R(Call Alice)", "R(Mail Alice)"))
                            .copy(contentDescription = "R(desc node_a)"),
                        element("node_b", null, emptyList()).copy(contentDescription = "R(desc node_b)"),
                        element("node_c", "R(Carol)", listOf("R(Upvote)")).copy(contentDescription = "R(desc node_c)"),
                    ),
                    redacted,
                )
            }
    }
}
