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
     *
     * @throws McpToolException.InvalidParams if the parameter is missing or not a number.
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
        return primitive.content.toFloatOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got: '${primitive.content}'",
            )
    }

    /**
     * Extracts an optional numeric value from [params] as a [Float],
     * returning [default] if not present.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a valid number.
     */
    fun optionalFloat(
        params: JsonObject?,
        name: String,
        default: Float,
    ): Float {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        return primitive.content.toFloatOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got: '${primitive.content}'",
            )
    }

    /**
     * Extracts an optional numeric value from [params] as a [Long],
     * returning [default] if not present.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a valid number.
     */
    fun optionalLong(
        params: JsonObject?,
        name: String,
        default: Long,
    ): Long {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        return primitive.content.toDoubleOrNull()?.toLong()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got: '${primitive.content}'",
            )
    }

    /**
     * Extracts a required string value from [params].
     *
     * @throws McpToolException.InvalidParams if the parameter is missing or not a string.
     */
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
        return primitive.content
    }

    /**
     * Extracts an optional string value from [params],
     * returning [default] if not present.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a string.
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
