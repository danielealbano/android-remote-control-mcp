@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * MCP tool: input_text
 *
 * Types text into a specified element or the currently focused input field.
 * If element_id is provided, clicks it to focus and then sets the text.
 * If element_id is not provided, finds the currently focused editable node.
 */
class InputTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val text =
                params?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'text'")

            val elementId = params["element_id"]?.jsonPrimitive?.contentOrNull

            if (elementId != null && elementId.isNotEmpty()) {
                // Target specific element: click to focus, then set text
                val tree = getFreshTree(treeParser)
                val clickResult = actionExecutor.clickNode(elementId, tree)
                clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                // Re-parse after click (focus may have changed the tree)
                val freshTree = getFreshTree(treeParser)
                val setResult = actionExecutor.setTextOnNode(elementId, text, freshTree)
                setResult.onFailure { e -> mapNodeActionException(e, elementId) }

                Log.d(TAG, "input_text: set text on element '$elementId', length=${text.length}")
            } else {
                // Find currently focused editable node
                val focusedNode =
                    findFocusedEditableNode()
                        ?: throw McpToolException.ElementNotFound(
                            "No focused editable element found. Focus an input field first or provide element_id.",
                        )

                try {
                    val arguments =
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text,
                            )
                        }
                    val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (!success) {
                        throw McpToolException.ActionFailed(
                            "Failed to set text on focused element",
                        )
                    }
                    Log.d(TAG, "input_text: set text on focused element, length=${text.length}")
                } finally {
                    @Suppress("DEPRECATION")
                    focusedNode.recycle()
                }
            }

            return McpContentBuilder.textContent("Text input completed (${text.length} characters)")
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Type text into the focused input field or a specified element",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Text to type")
                            }
                            putJsonObject("element_id") {
                                put("type", "string")
                                put("description", "Optional: target element ID to focus and type into")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("text")) })
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:InputTextTool"
            private const val TOOL_NAME = "input_text"
        }
    }

/**
 * MCP tool: clear_text
 *
 * Clears text from a specified element or the currently focused input field.
 */
class ClearTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val elementId = params?.get("element_id")?.jsonPrimitive?.contentOrNull

            if (elementId != null && elementId.isNotEmpty()) {
                // Clear specific element's text
                val tree = getFreshTree(treeParser)
                val result = actionExecutor.setTextOnNode(elementId, "", tree)
                result.onFailure { e -> mapNodeActionException(e, elementId) }

                Log.d(TAG, "clear_text: cleared text on element '$elementId'")
            } else {
                // Find currently focused editable node and clear it
                val focusedNode =
                    findFocusedEditableNode()
                        ?: throw McpToolException.ElementNotFound(
                            "No focused editable element found. Focus an input field first or provide element_id.",
                        )

                try {
                    val arguments =
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                "",
                            )
                        }
                    val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (!success) {
                        throw McpToolException.ActionFailed(
                            "Failed to clear text on focused element",
                        )
                    }
                    Log.d(TAG, "clear_text: cleared text on focused element")
                } finally {
                    @Suppress("DEPRECATION")
                    focusedNode.recycle()
                }
            }

            return McpContentBuilder.textContent("Text cleared successfully")
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Clear text from the focused input field or a specified element",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("element_id") {
                                put("type", "string")
                                put("description", "Optional: target element ID to clear")
                            }
                        }
                        put("required", buildJsonArray {})
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:ClearTextTool"
            private const val TOOL_NAME = "clear_text"
        }
    }

/**
 * MCP tool: press_key
 *
 * Presses a specific key. Supported keys: ENTER, BACK, DEL, HOME, TAB, SPACE.
 *
 * Key mapping strategy:
 * - BACK, HOME: Delegate to ActionExecutor global actions (already implemented).
 * - ENTER: Use ACTION_IME_ENTER on API 30+, fallback to appending newline via ACTION_SET_TEXT.
 * - DEL: Get current text from focused node, remove last character, set text.
 * - TAB, SPACE: Get current text from focused node, append character, set text.
 */
class PressKeyTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val key =
                params?.get("key")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'key'")

            val upperKey = key.uppercase()
            if (upperKey !in ALLOWED_KEYS) {
                throw McpToolException.InvalidParams(
                    "Invalid key: '$key'. Allowed values: ${ALLOWED_KEYS.joinToString(", ")}",
                )
            }

            when (upperKey) {
                "BACK" -> {
                    val result = actionExecutor.pressBack()
                    result.onFailure { e ->
                        throw McpToolException.ActionFailed("BACK key failed: ${e.message}")
                    }
                }
                "HOME" -> {
                    val result = actionExecutor.pressHome()
                    result.onFailure { e ->
                        throw McpToolException.ActionFailed("HOME key failed: ${e.message}")
                    }
                }
                "ENTER" -> pressEnter()
                "DEL" -> pressDelete()
                "TAB" -> appendCharToFocused('\t')
                "SPACE" -> appendCharToFocused(' ')
            }

            Log.d(TAG, "press_key: key=$upperKey succeeded")
            return McpContentBuilder.textContent("Key '$upperKey' pressed successfully")
        }

        private fun pressEnter() {
            val focusedNode =
                findFocusedEditableNode()
                    ?: throw McpToolException.ElementNotFound(
                        "No focused element found for ENTER key",
                    )

            try {
                val success =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } else {
                        // Fallback for API < 30: append newline character
                        val currentText = focusedNode.text?.toString() ?: ""
                        val arguments =
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    currentText + "\n",
                                )
                            }
                        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    }
                if (!success) {
                    throw McpToolException.ActionFailed("ENTER key action failed")
                }
            } finally {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            }
        }

        private fun pressDelete() {
            val focusedNode =
                findFocusedEditableNode()
                    ?: throw McpToolException.ElementNotFound(
                        "No focused editable element found for DEL key",
                    )

            try {
                val currentText = focusedNode.text?.toString() ?: ""
                if (currentText.isNotEmpty()) {
                    val newText = currentText.dropLast(1)
                    val arguments =
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                newText,
                            )
                        }
                    val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (!success) {
                        throw McpToolException.ActionFailed("DEL key action failed")
                    }
                }
                // If text is already empty, DEL is a no-op (not an error)
            } finally {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            }
        }

        private fun appendCharToFocused(char: Char) {
            val focusedNode =
                findFocusedEditableNode()
                    ?: throw McpToolException.ElementNotFound(
                        "No focused editable element found for key input",
                    )

            try {
                val currentText = focusedNode.text?.toString() ?: ""
                val arguments =
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            currentText + char,
                        )
                    }
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (!success) {
                    throw McpToolException.ActionFailed("Key input action failed")
                }
            } finally {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Press a specific key (ENTER, BACK, DEL, HOME, TAB, SPACE)",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("key") {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("ENTER"))
                                        add(JsonPrimitive("BACK"))
                                        add(JsonPrimitive("DEL"))
                                        add(JsonPrimitive("HOME"))
                                        add(JsonPrimitive("TAB"))
                                        add(JsonPrimitive("SPACE"))
                                    },
                                )
                                put("description", "Key to press")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("key")) })
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:PressKeyTool"
            private const val TOOL_NAME = "press_key"
            private val ALLOWED_KEYS = setOf("ENTER", "BACK", "DEL", "HOME", "TAB", "SPACE")
        }
    }

/**
 * Registers all text input tools with the [ToolRegistry].
 */
fun registerTextInputTools(toolRegistry: ToolRegistry) {
    val treeParser = AccessibilityTreeParser()
    val actionExecutor = ActionExecutor()
    InputTextTool(treeParser, actionExecutor).register(toolRegistry)
    ClearTextTool(treeParser, actionExecutor).register(toolRegistry)
    PressKeyTool(actionExecutor).register(toolRegistry)
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Utility for Text Input Tools
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Finds the currently input-focused editable node using the accessibility service.
 *
 * Uses [AccessibilityService.findFocus] with [AccessibilityNodeInfo.FOCUS_INPUT]
 * to locate the node that currently has keyboard focus.
 *
 * @return The focused [AccessibilityNodeInfo], or null if no editable node is focused.
 *         The caller is responsible for recycling the returned node.
 */
@Suppress("ReturnCount")
internal fun findFocusedEditableNode(): AccessibilityNodeInfo? {
    val service =
        McpAccessibilityService.instance
            ?: throw McpToolException.PermissionDenied(
                "Accessibility service is not enabled",
            )

    val rootNode = service.getRootNode() ?: return null
    val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    @Suppress("DEPRECATION")
    rootNode.recycle()

    if (focusedNode != null && !focusedNode.isEditable) {
        @Suppress("DEPRECATION")
        focusedNode.recycle()
        return null
    }

    return focusedNode
}
