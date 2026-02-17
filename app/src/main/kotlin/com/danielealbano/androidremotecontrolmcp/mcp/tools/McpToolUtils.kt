package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared utilities for MCP tool parameter extraction and response building.
 *
 * Provides helper functions to extract numeric values from [JsonObject] params
 * (handling both Int and Double JSON number types), validate parameter values,
 * and translate [Result] failures into appropriate [McpToolException] subtypes.
 */
@Suppress("TooManyFunctions")
internal object McpToolUtils {
    /**
     * Extracts a required numeric value from [params] as a [Float].
     *
     * Handles both integer and floating-point JSON numbers.
     * Rejects string-encoded numbers (e.g., `"500"`) and non-finite values (NaN, Infinity).
     *
     * @throws McpToolException.InvalidParams if the parameter is missing, not a number,
     *         is a string-encoded number, or is non-finite.
     */
    @Suppress("ThrowsCount")
    fun requireFloat(
        params: JsonObject?,
        name: String,
    ): Float {
        val element =
            params?.get(name)
                ?: throw McpToolException.InvalidParams("Missing required parameter: '$name'")
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got string: '${primitive.content}'",
            )
        }
        val value =
            primitive.content.toFloatOrNull()
                ?: throw McpToolException.InvalidParams(
                    "Parameter '$name' must be a number, got: '${primitive.content}'",
                )
        if (!value.isFinite()) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a finite number, got: '${primitive.content}'",
            )
        }
        return value
    }

    /**
     * Extracts a required integer value from [params].
     *
     * Accepts JSON numbers only (not string-encoded). Rejects floats (e.g. 3.5).
     *
     * @throws McpToolException.InvalidParams if the parameter is missing, not a number,
     *   or not an integer.
     */
    @Suppress("ThrowsCount")
    fun requireInt(
        params: JsonObject?,
        name: String,
    ): Int {
        val element =
            params?.get(name)
                ?: throw McpToolException.InvalidParams("Missing required parameter: '$name'")
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got string: '${primitive.content}'",
            )
        }
        val doubleVal =
            primitive.content.toDoubleOrNull()
                ?: throw McpToolException.InvalidParams(
                    "Parameter '$name' must be a number, got: '${primitive.content}'",
                )
        val intVal = doubleVal.toInt()
        if (doubleVal != intVal.toDouble()) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be an integer, got: '${primitive.content}'",
            )
        }
        return intVal
    }

    /**
     * Extracts an optional numeric value from [params] as a [Float],
     * returning [default] if not present.
     *
     * Rejects string-encoded numbers and non-finite values.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a valid number,
     *         is a string-encoded number, or is non-finite.
     */
    @Suppress("ThrowsCount")
    fun optionalFloat(
        params: JsonObject?,
        name: String,
        default: Float,
    ): Float {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got string: '${primitive.content}'",
            )
        }
        val value =
            primitive.content.toFloatOrNull()
                ?: throw McpToolException.InvalidParams(
                    "Parameter '$name' must be a number, got: '${primitive.content}'",
                )
        if (!value.isFinite()) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a finite number, got: '${primitive.content}'",
            )
        }
        return value
    }

    /**
     * Extracts an optional numeric value from [params] as a [Long],
     * returning [default] if not present.
     *
     * Rejects string-encoded numbers and fractional values (e.g., `1.5` is rejected;
     * `1.0` is accepted as `1L` since JSON does not distinguish integer from float notation).
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a valid integer,
     *         is a string-encoded number, or has a fractional component.
     */
    @Suppress("ThrowsCount")
    fun optionalLong(
        params: JsonObject?,
        name: String,
        default: Long,
    ): Long {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got string: '${primitive.content}'",
            )
        }
        val doubleVal =
            primitive.content.toDoubleOrNull()
                ?: throw McpToolException.InvalidParams(
                    "Parameter '$name' must be a number, got: '${primitive.content}'",
                )
        val longVal = doubleVal.toLong()
        if (doubleVal != longVal.toDouble()) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be an integer, got: '${primitive.content}'",
            )
        }
        return longVal
    }

    /**
     * Extracts an optional integer value from [params],
     * returning [default] if not present.
     *
     * Rejects string-encoded numbers, fractional values, and values outside Int range.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a valid integer.
     */
    @Suppress("ThrowsCount")
    fun optionalInt(
        params: JsonObject?,
        name: String,
        default: Int,
    ): Int {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got string: '${primitive.content}'",
            )
        }
        val doubleVal =
            primitive.content.toDoubleOrNull()
                ?: throw McpToolException.InvalidParams(
                    "Parameter '$name' must be a number, got: '${primitive.content}'",
                )
        val intVal = doubleVal.toInt()
        if (doubleVal != intVal.toDouble()) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be an integer, got: '${primitive.content}'",
            )
        }
        return intVal
    }

    /**
     * Extracts an optional boolean value from [params],
     * returning [default] if not present.
     *
     * Accepts JSON booleans (true/false). Rejects string-encoded booleans.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a boolean.
     */
    @Suppress("ThrowsCount")
    fun optionalBoolean(
        params: JsonObject?,
        name: String,
        default: Boolean,
    ): Boolean {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a boolean")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a boolean (true/false), got string: '${primitive.content}'",
            )
        }
        return primitive.content.toBooleanStrictOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$name' must be a boolean (true/false), got: '${primitive.content}'",
            )
    }

    /**
     * Extracts a required string value from [params].
     *
     * Rejects non-string primitives (e.g., numeric `123` instead of `"123"`).
     *
     * @throws McpToolException.InvalidParams if the parameter is missing, not a primitive,
     *         or is a non-string primitive (number, boolean).
     */
    @Suppress("ThrowsCount")
    fun requireString(
        params: JsonObject?,
        name: String,
    ): String {
        val element =
            params?.get(name)
                ?: throw McpToolException.InvalidParams("Missing required parameter: '$name'")
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a string")
        if (!primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a string, got: ${primitive.content}",
            )
        }
        return primitive.content
    }

    /**
     * Extracts an optional string value from [params],
     * returning [default] if not present.
     *
     * Rejects non-string primitives (e.g., numeric `123` instead of `"123"`).
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a string,
     *         or is a non-string primitive (number, boolean).
     */
    fun optionalString(
        params: JsonObject?,
        name: String,
        default: String,
    ): String {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a string")
        if (!primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a string, got: ${primitive.content}",
            )
        }
        return primitive.content
    }

    /**
     * Validates that [value] is >= 0.
     *
     * @throws McpToolException.InvalidParams if validation fails.
     */
    fun validateNonNegative(
        value: Float,
        name: String,
    ) {
        if (value < 0f) {
            throw McpToolException.InvalidParams("Parameter '$name' must be >= 0, got: $value")
        }
    }

    /**
     * Validates that [value] is > 0 and <= [max].
     *
     * @throws McpToolException.InvalidParams if validation fails.
     */
    fun validatePositiveRange(
        value: Long,
        name: String,
        max: Long,
    ) {
        if (value <= 0L || value > max) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be between 1 and $max, got: $value",
            )
        }
    }

    /**
     * Translates an action [Result.failure] into the appropriate [McpToolException].
     *
     * - [IllegalStateException] with "not available" -> [McpToolException.PermissionDenied]
     * - All other exceptions -> [McpToolException.ActionFailed]
     *
     * On success, returns a [CallToolResult] with [successMessage] as [TextContent].
     */
    fun handleActionResult(
        result: Result<Unit>,
        successMessage: String,
    ): CallToolResult {
        if (result.isSuccess) {
            return textResult(successMessage)
        }

        val exception = result.exceptionOrNull()!!
        val message = exception.message ?: "Unknown error"

        if (exception is IllegalStateException && message.contains("not available")) {
            throw McpToolException.PermissionDenied(
                "Accessibility service not enabled. Please enable it in Android Settings.",
            )
        }

        throw McpToolException.ActionFailed(message)
    }

    /**
     * Creates a [CallToolResult] containing a single [TextContent] item.
     */
    fun textResult(text: String): CallToolResult = CallToolResult(content = listOf(TextContent(text = text)))

    /**
     * Creates a [CallToolResult] containing a single [ImageContent] item.
     */
    fun imageResult(
        data: String,
        mimeType: String,
    ): CallToolResult = CallToolResult(content = listOf(ImageContent(data = data, mimeType = mimeType)))

    /**
     * Creates a [CallToolResult] containing a [TextContent] item followed by an [ImageContent] item.
     */
    fun textAndImageResult(
        text: String,
        imageData: String,
        imageMimeType: String,
    ): CallToolResult =
        CallToolResult(
            content =
                listOf(
                    TextContent(text = text),
                    ImageContent(data = imageData, mimeType = imageMimeType),
                ),
        )

    /** Maximum duration in milliseconds for any gesture/action. */
    const val MAX_DURATION_MS = 60000L

    /** Base prefix for all MCP tool names. */
    private const val TOOL_NAME_BASE_PREFIX = "android"

    /**
     * Builds the tool name prefix string from the device slug.
     *
     * - Empty slug: `"android_"`
     * - Non-empty slug: `"android_<slug>_"` (e.g., `"android_pixel7_"`)
     *
     * The returned prefix is intended to be concatenated with the tool's base name
     * (e.g., `"${prefix}tap"` → `"android_tap"` or `"android_pixel7_tap"`).
     *
     * @param deviceSlug The optional device slug (empty string means no slug).
     * @return The prefix string ending with `_`.
     */
    fun buildToolNamePrefix(deviceSlug: String): String =
        if (deviceSlug.isEmpty()) {
            "${TOOL_NAME_BASE_PREFIX}_"
        } else {
            "${TOOL_NAME_BASE_PREFIX}_${deviceSlug}_"
        }

    /**
     * Builds the MCP server implementation name, optionally including the device slug.
     *
     * - Empty slug: `"android-remote-control-mcp"`
     * - Non-empty slug: `"android-remote-control-mcp-<slug>"` (e.g., `"android-remote-control-mcp-pixel7"`)
     *
     * @param deviceSlug The optional device slug (empty string means no slug).
     * @return The server implementation name.
     */
    fun buildServerName(deviceSlug: String): String =
        if (deviceSlug.isEmpty()) {
            "android-remote-control-mcp"
        } else {
            "android-remote-control-mcp-$deviceSlug"
        }
}
