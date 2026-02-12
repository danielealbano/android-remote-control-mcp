package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared utilities for MCP tool parameter extraction and response building.
 *
 * Provides helper functions to extract numeric values from [JsonObject] params
 * (handling both Int and Double JSON number types), validate parameter values,
 * and translate [Result] failures into appropriate [McpToolException] subtypes.
 */
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
     * On success, returns the result of [McpContentBuilder.textContent] with [successMessage].
     */
    fun handleActionResult(
        result: Result<Unit>,
        successMessage: String,
    ): JsonElement {
        if (result.isSuccess) {
            return McpContentBuilder.textContent(successMessage)
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

    /** Maximum duration in milliseconds for any gesture/action. */
    const val MAX_DURATION_MS = 60000L
}
