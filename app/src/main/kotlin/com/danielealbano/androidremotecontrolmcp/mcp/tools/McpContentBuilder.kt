package com.danielealbano.androidremotecontrolmcp.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Utility for building MCP tool response content in the standard format.
 *
 * MCP tool responses wrap results in a `content` array with typed entries:
 * - Text: `{ "content": [{ "type": "text", "text": "..." }] }`
 * - Image: `{ "content": [{ "type": "image", "data": "...", "mimeType": "..." }] }`
 */
object McpContentBuilder {
    /**
     * Builds a text content response.
     *
     * @param text The text content (can be plain text or JSON string).
     * @return JsonElement wrapping the text in MCP content format.
     */
    fun textContent(text: String): JsonElement {
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
        }
    }

    /**
     * Builds an image content response.
     *
     * @param data The base64-encoded image data.
     * @param mimeType The MIME type (e.g., "image/jpeg").
     * @param width The image width in pixels (optional, included when available).
     * @param height The image height in pixels (optional, included when available).
     * @return JsonElement wrapping the image in MCP content format.
     */
    fun imageContent(
        data: String,
        mimeType: String,
        width: Int? = null,
        height: Int? = null,
    ): JsonElement {
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put("data", data)
                            put("mimeType", mimeType)
                            if (width != null) put("width", width)
                            if (height != null) put("height", height)
                        },
                    )
                },
            )
        }
    }
}
