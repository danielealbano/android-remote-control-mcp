@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// Shared constants for type tools
private const val MAX_TEXT_LENGTH = 2000
private const val DEFAULT_TYPING_SPEED_MS = 70
private const val DEFAULT_TYPING_VARIANCE_MS = 15
private const val MIN_TYPING_SPEED_MS = 10
private const val MAX_TYPING_SPEED_MS = 5000
private const val MAX_SURROUNDING_TEXT_LENGTH = 10000
private const val FOCUS_POLL_INTERVAL_MS = 50L
private const val FOCUS_POLL_MAX_MS = 500L

/**
 * Mutex serializing all type tool operations.
 * Prevents concurrent MCP requests from interleaving character commits.
 *
 * **Hold time**: The Mutex is held for the entire duration of a typing operation.
 * For 2000 chars at default 70ms: ~140 seconds. At max 5000ms: ~2.8 hours.
 * During this time, other type tool MCP requests are suspended (queued).
 * Non-type tools (tap, swipe, screenshot, etc.) are NOT blocked.
 */
internal val typeOperationMutex = Mutex()

/**
 * Types text code point by code point using the given [TypeInputController],
 * with configurable speed and variance to simulate natural human typing.
 *
 * Iterates by **Unicode code points** (not Char), so supplementary characters
 * (emoji, CJK extensions) are committed as a single unit instead of being
 * split into surrogate pairs.
 *
 * Each code point is committed via [TypeInputController.commitText] with a delay of:
 *   typingSpeed + random(-effectiveVariance, +effectiveVariance)
 * where effectiveVariance = clamp(variance, 0, typingSpeed).
 *
 * Supports cancellation via structured concurrency — if the parent coroutine
 * is cancelled, the typing loop stops immediately at the next `delay()`.
 *
 * **Input connection loss detection**: Instead of checking `isReady()` before
 * every character (which can cause false negatives during brief framework state
 * transitions), this function relies solely on the `commitText()` return value.
 * If `commitText` returns `false`, the input connection has been lost and typing
 * stops immediately with an error.
 *
 * @param text The text to type.
 * @param typingSpeed Base delay between characters in ms.
 * @param typingSpeedVariance Variance in ms.
 * @param typeInputController The input controller to commit characters through.
 * @throws McpToolException.ActionFailed if commitText fails (input connection lost mid-typing).
 */
internal suspend fun typeCharByChar(
    text: String,
    typingSpeed: Int,
    typingSpeedVariance: Int,
    typeInputController: TypeInputController,
) {
    val effectiveVariance = typingSpeedVariance.coerceIn(0, typingSpeed)
    val codePointCount = text.codePointCount(0, text.length)
    var codePointIndex = 0
    var offset = 0

    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val charCount = Character.charCount(codePoint)
        val codePointStr = text.substring(offset, offset + charCount)

        val committed = typeInputController.commitText(codePointStr, 1)
        if (!committed) {
            throw McpToolException.ActionFailed(
                "Input connection lost during typing at position $codePointIndex of $codePointCount. " +
                    "commitText returned false.",
            )
        }

        offset += charCount
        codePointIndex++

        // Skip delay after the last character to avoid unnecessary wait
        if (offset < text.length) {
            val delay =
                if (effectiveVariance > 0) {
                    val variance = kotlin.random.Random.nextInt(-effectiveVariance, effectiveVariance + 1)
                    (typingSpeed + variance).coerceAtLeast(1).toLong()
                } else {
                    typingSpeed.toLong()
                }
            delay(delay)
        }
    }
}

/**
 * Validates and extracts typing speed parameters from tool arguments.
 * Uses [McpToolUtils.optionalInt] for consistent parameter parsing with
 * proper type checking (rejects string-encoded numbers, floats, etc.).
 *
 * @return Pair of (typingSpeed, typingSpeedVariance) in ms.
 * @throws McpToolException.InvalidParams if values are out of range.
 */
@Suppress("ThrowsCount")
internal fun extractTypingParams(arguments: JsonObject?): Pair<Int, Int> {
    val typingSpeed = McpToolUtils.optionalInt(arguments, "typing_speed", DEFAULT_TYPING_SPEED_MS)

    if (typingSpeed < MIN_TYPING_SPEED_MS) {
        throw McpToolException.InvalidParams(
            "typing_speed must be >= $MIN_TYPING_SPEED_MS ms, got $typingSpeed",
        )
    }
    if (typingSpeed > MAX_TYPING_SPEED_MS) {
        throw McpToolException.InvalidParams(
            "typing_speed must be <= $MAX_TYPING_SPEED_MS ms, got $typingSpeed",
        )
    }

    val typingSpeedVariance = McpToolUtils.optionalInt(arguments, "typing_speed_variance", DEFAULT_TYPING_VARIANCE_MS)

    if (typingSpeedVariance < 0) {
        throw McpToolException.InvalidParams(
            "typing_speed_variance must be >= 0, got $typingSpeedVariance",
        )
    }

    return typingSpeed to typingSpeedVariance
}

/**
 * Validates that text length does not exceed the maximum.
 *
 * @throws McpToolException.InvalidParams if text exceeds MAX_TEXT_LENGTH.
 */
internal fun validateTextLength(
    text: String,
    paramName: String = "text",
) {
    if (text.length > MAX_TEXT_LENGTH) {
        throw McpToolException.InvalidParams(
            "$paramName exceeds maximum length of $MAX_TEXT_LENGTH characters (got ${text.length})",
        )
    }
}

/**
 * Polls for TypeInputController readiness after clicking an element to focus it.
 * Uses a poll-retry loop instead of a fixed delay to minimize unnecessary waiting
 * while still giving the framework time to establish the InputConnection.
 *
 * Uses wall-clock time (`System.currentTimeMillis()`) for accurate timeout
 * tracking, since `delay()` is a suspension point and may resume later than
 * the requested interval.
 *
 * **Testing note**: In unit tests using `runTest`, `delay()` is auto-advanced by the
 * test dispatcher but `System.currentTimeMillis()` is NOT. The timeout test will
 * consume real wall-clock time (~500ms). This is acceptable for a unit test.
 *
 * @param typeInputController The controller to check.
 * @param elementId The element ID (for error message).
 * @throws McpToolException.ActionFailed if not ready after FOCUS_POLL_MAX_MS.
 */
internal suspend fun awaitInputConnectionReady(
    typeInputController: TypeInputController,
    elementId: String,
) {
    val deadline = System.currentTimeMillis() + FOCUS_POLL_MAX_MS
    while (System.currentTimeMillis() < deadline) {
        if (typeInputController.isReady()) return
        delay(FOCUS_POLL_INTERVAL_MS)
    }
    throw McpToolException.ActionFailed(
        "Input connection not available after focusing element '$elementId'. " +
            "The element may not be an editable text field.",
    )
}

/**
 * Reads the current field content after an operation completes.
 * Used to include the field content in the tool response so the model
 * can verify the result.
 *
 * Returns the field text as a String, or a fallback message if the
 * content could not be read (e.g., input connection lost after the operation).
 * This is best-effort — a read failure does NOT cause the tool to fail,
 * since the operation itself already succeeded.
 *
 * @param typeInputController The controller to read from.
 * @return The field content as a String.
 */
internal fun readFieldContent(typeInputController: TypeInputController): String {
    val surroundingText =
        typeInputController.getSurroundingText(
            MAX_SURROUNDING_TEXT_LENGTH,
            MAX_SURROUNDING_TEXT_LENGTH,
            0,
        ) ?: return "(unable to read field content)"
    return surroundingText.text.toString()
}

class TypeAppendTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val text = McpToolUtils.requireString(arguments, "text")
            if (text.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'text' must be non-empty")
            }
            validateTextLength(text)

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            val fieldContent =
                typeOperationMutex.withLock {
                    // Click to focus
                    val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                    val clickResult = actionExecutor.clickNode(elementId, tree)
                    clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                    // Poll-retry for InputConnection readiness (max 500ms, 50ms interval)
                    awaitInputConnectionReady(typeInputController, elementId)

                    // Position cursor at end
                    // Note: offset + text.length gives the total text length only if
                    // getSurroundingText returns the complete field content. For fields
                    // with >2*MAX_SURROUNDING_TEXT_LENGTH chars, this may undercount.
                    // Given the 2000-char tool limit, this is not a practical concern.
                    val surroundingText =
                        typeInputController.getSurroundingText(
                            MAX_SURROUNDING_TEXT_LENGTH,
                            MAX_SURROUNDING_TEXT_LENGTH,
                            0,
                        )
                    val textLength =
                        surroundingText?.let {
                            it.offset + it.text.length
                        } ?: 0
                    if (!typeInputController.setSelection(textLength, textLength)) {
                        throw McpToolException.ActionFailed(
                            "Failed to position cursor in element '$elementId' — input connection lost",
                        )
                    }

                    // Type code point by code point
                    typeCharByChar(text, typingSpeed, typingSpeedVariance, typeInputController)

                    // Read field content after operation for verification
                    readFieldContent(typeInputController)
                }

            Log.d(TAG, "type_append_text: typed ${text.length} chars on element '$elementId'")
            return McpToolUtils.textResult(
                "Typed ${text.length} characters at end of element '$elementId'.\n" +
                    "Field content: $fieldContent",
            )
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Type text character by character at the end of a text field. " +
                        "Uses natural InputConnection typing (indistinguishable from keyboard input). " +
                        "Maximum text length: $MAX_TEXT_LENGTH characters. " +
                        "For text longer than $MAX_TEXT_LENGTH chars, call this tool multiple times — " +
                        "subsequent calls continue typing at the current cursor position. " +
                        "Returns the field content after the operation for verification.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Target element ID to type into")
                                }
                                putJsonObject("text") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Text to type (must be non-empty, " +
                                            "max $MAX_TEXT_LENGTH characters)",
                                    )
                                }
                                putJsonObject("typing_speed") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Base delay between characters in ms " +
                                            "(default: $DEFAULT_TYPING_SPEED_MS, " +
                                            "min: $MIN_TYPING_SPEED_MS, " +
                                            "max: $MAX_TYPING_SPEED_MS)",
                                    )
                                }
                                putJsonObject("typing_speed_variance") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Random variance in ms, clamped to " +
                                            "[0, typing_speed] " +
                                            "(default: $DEFAULT_TYPING_VARIANCE_MS)",
                                    )
                                }
                            },
                        required = listOf("element_id", "text"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeAppendTextTool"
            private const val TOOL_NAME = "type_append_text"
        }
    }

class TypeInsertTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val text = McpToolUtils.requireString(arguments, "text")
            if (text.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'text' must be non-empty")
            }
            validateTextLength(text)

            // Parse offset with proper type checking (see note above)
            val offset = McpToolUtils.requireInt(arguments, "offset")
            if (offset < 0) {
                throw McpToolException.InvalidParams("Parameter 'offset' must be >= 0, got $offset")
            }

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            val fieldContent =
                typeOperationMutex.withLock {
                    // Click to focus
                    val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                    val clickResult = actionExecutor.clickNode(elementId, tree)
                    clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                    // Poll-retry for InputConnection readiness
                    awaitInputConnectionReady(typeInputController, elementId)

                    // Validate offset against current text length
                    val surroundingText =
                        typeInputController.getSurroundingText(
                            MAX_SURROUNDING_TEXT_LENGTH,
                            MAX_SURROUNDING_TEXT_LENGTH,
                            0,
                        )
                    val textLength =
                        surroundingText?.let {
                            it.offset + it.text.length
                        } ?: 0

                    if (offset > textLength) {
                        throw McpToolException.InvalidParams(
                            "offset ($offset) exceeds text length ($textLength) in element '$elementId'",
                        )
                    }

                    // Position cursor at offset — check return value
                    if (!typeInputController.setSelection(offset, offset)) {
                        throw McpToolException.ActionFailed(
                            "Failed to position cursor at offset $offset " +
                                "in element '$elementId' — input connection lost",
                        )
                    }

                    // Type code point by code point
                    typeCharByChar(text, typingSpeed, typingSpeedVariance, typeInputController)

                    // Read field content after operation for verification
                    readFieldContent(typeInputController)
                }

            Log.d(TAG, "type_insert_text: typed ${text.length} chars at offset $offset on '$elementId'")
            return McpToolUtils.textResult(
                "Typed ${text.length} characters at offset $offset in element '$elementId'.\n" +
                    "Field content: $fieldContent",
            )
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Type text character by character at a specific position in a text field. " +
                        "Uses natural InputConnection typing (indistinguishable from keyboard input). " +
                        "Maximum text length: $MAX_TEXT_LENGTH characters. " +
                        "Returns the field content after the operation for verification.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Target element ID to type into")
                                }
                                putJsonObject("text") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Text to type (must be non-empty, " +
                                            "max $MAX_TEXT_LENGTH characters)",
                                    )
                                }
                                putJsonObject("offset") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "0-based character offset for cursor " +
                                            "position. Must be within " +
                                            "[0, current text length].",
                                    )
                                }
                                putJsonObject("typing_speed") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Base delay between characters in ms " +
                                            "(default: $DEFAULT_TYPING_SPEED_MS, " +
                                            "min: $MIN_TYPING_SPEED_MS, " +
                                            "max: $MAX_TYPING_SPEED_MS)",
                                    )
                                }
                                putJsonObject("typing_speed_variance") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Random variance in ms, clamped to " +
                                            "[0, typing_speed] " +
                                            "(default: $DEFAULT_TYPING_VARIANCE_MS)",
                                    )
                                }
                            },
                        required = listOf("element_id", "text", "offset"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeInsertTextTool"
            private const val TOOL_NAME = "type_insert_text"
        }
    }

/**
 * MCP tool: type_replace_text
 *
 * Finds the first occurrence of search text in a field, selects it,
 * deletes it via DELETE key event, then types the replacement text
 * code point by code point using the InputConnection pipeline.
 *
 * **Known limitation (TOCTOU race)**: There is an inherent time-of-check to
 * time-of-use race between `getSurroundingText()` (read) and `setSelection()`
 * (write). If the target app modifies the text field between these two calls
 * (e.g., via autocomplete, autofill, or background updates), the selection
 * indices may be wrong. The `typeOperationMutex` prevents concurrent MCP
 * requests from racing, but cannot prevent the target app itself from
 * modifying its own text. This is inherent to the approach and unavoidable
 * without framework-level atomic read-select operations.
 *
 * Flow:
 * 1. Click element_id to focus
 * 2. Get current text via getSurroundingText()
 * 3. Find first occurrence of search text — error if not found
 * 4. setSelection(startIndex, endIndex) to select found text
 * 5. Send DELETE key event to remove selection
 * 6. Type new_text code point by code point via commitText() (if non-empty)
 */
class TypeReplaceTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount", "LongMethod")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val search = McpToolUtils.requireString(arguments, "search")
            if (search.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'search' must be non-empty")
            }
            // Validate search length — bounded by getSurroundingText buffer
            if (search.length > MAX_SURROUNDING_TEXT_LENGTH) {
                throw McpToolException.InvalidParams(
                    "search exceeds maximum length of $MAX_SURROUNDING_TEXT_LENGTH characters (got ${search.length})",
                )
            }

            val newText = McpToolUtils.requireString(arguments, "new_text")
            if (newText.isNotEmpty()) {
                validateTextLength(newText, "new_text")
            }

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            val fieldContent =
                typeOperationMutex.withLock {
                    // Click to focus
                    val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                    val clickResult = actionExecutor.clickNode(elementId, tree)
                    clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                    // Poll-retry for InputConnection readiness
                    awaitInputConnectionReady(typeInputController, elementId)

                    // Get current text and find the search string
                    val surroundingText =
                        typeInputController.getSurroundingText(
                            MAX_SURROUNDING_TEXT_LENGTH,
                            MAX_SURROUNDING_TEXT_LENGTH,
                            0,
                        ) ?: throw McpToolException.ActionFailed(
                            "Unable to read text from element '$elementId'",
                        )

                    val fullText = surroundingText.text.toString()
                    val searchIndex = fullText.indexOf(search)
                    if (searchIndex == -1) {
                        throw McpToolException.ElementNotFound(
                            "Search text (${search.length} chars) not found in element '$elementId'",
                        )
                    }

                    // Adjust index relative to the actual field (account for surroundingText offset)
                    val absoluteStart = surroundingText.offset + searchIndex
                    val absoluteEnd = absoluteStart + search.length

                    // Select the found text — check return value
                    if (!typeInputController.setSelection(absoluteStart, absoluteEnd)) {
                        throw McpToolException.ActionFailed(
                            "Failed to select text in element '$elementId' — input connection lost",
                        )
                    }

                    // Delete the selection via DELETE key event — check return values
                    if (!typeInputController.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
                        )
                    ) {
                        throw McpToolException.ActionFailed(
                            "Failed to send DELETE key (ACTION_DOWN) on element '$elementId' — input connection lost",
                        )
                    }
                    if (!typeInputController.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL),
                        )
                    ) {
                        throw McpToolException.ActionFailed(
                            "Failed to send DELETE key (ACTION_UP) on element '$elementId' — input connection lost",
                        )
                    }

                    // Type replacement text code point by code point (if non-empty)
                    if (newText.isNotEmpty()) {
                        typeCharByChar(newText, typingSpeed, typingSpeedVariance, typeInputController)
                    }

                    // Read field content after operation for verification
                    readFieldContent(typeInputController)
                }

            Log.d(
                TAG,
                "type_replace_text: replaced ${search.length} chars " +
                    "with ${newText.length} chars on '$elementId'",
            )
            return McpToolUtils.textResult(
                "Replaced ${search.length} characters with ${newText.length} characters in element '$elementId'.\n" +
                    "Field content: $fieldContent",
            )
        }

        @Suppress("LongMethod")
        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Find and replace text in a field by typing the replacement naturally. " +
                        "Finds the first occurrence of search text, deletes it, then types new_text " +
                        "character by character via InputConnection. " +
                        "Maximum new_text length: $MAX_TEXT_LENGTH characters. " +
                        "Returns error if search text is not found. " +
                        "Returns the field content after the operation for verification.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Target element ID")
                                }
                                putJsonObject("search") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Text to find in the field " +
                                            "(first occurrence, " +
                                            "max $MAX_SURROUNDING_TEXT_LENGTH chars)",
                                    )
                                }
                                putJsonObject("new_text") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Replacement text to type " +
                                            "(max $MAX_TEXT_LENGTH characters). " +
                                            "Can be empty to just delete " +
                                            "the found text.",
                                    )
                                }
                                putJsonObject("typing_speed") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Base delay between characters in ms " +
                                            "(default: $DEFAULT_TYPING_SPEED_MS, " +
                                            "min: $MIN_TYPING_SPEED_MS, " +
                                            "max: $MAX_TYPING_SPEED_MS)",
                                    )
                                }
                                putJsonObject("typing_speed_variance") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Random variance in ms, clamped to " +
                                            "[0, typing_speed] " +
                                            "(default: $DEFAULT_TYPING_VARIANCE_MS)",
                                    )
                                }
                            },
                        required = listOf("element_id", "search", "new_text"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeReplaceTextTool"
            private const val TOOL_NAME = "type_replace_text"
        }
    }

/**
 * MCP tool: type_clear_text
 *
 * Clears all text from a field naturally using InputConnection operations:
 * select all via context menu + DELETE key event.
 *
 * This is indistinguishable from a user performing select-all + backspace.
 *
 * If the field is already empty, returns success without performing any action.
 * All InputConnection return values are checked for robustness.
 */
class TypeClearTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val fieldContent =
                typeOperationMutex.withLock {
                    // Click to focus
                    val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                    val clickResult = actionExecutor.clickNode(elementId, tree)
                    clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                    // Poll-retry for InputConnection readiness
                    awaitInputConnectionReady(typeInputController, elementId)

                    // Check if field has text — skip clear if already empty
                    val surroundingText =
                        typeInputController.getSurroundingText(
                            MAX_SURROUNDING_TEXT_LENGTH,
                            MAX_SURROUNDING_TEXT_LENGTH,
                            0,
                        )
                    val textLength = surroundingText?.let { it.offset + it.text.length } ?: 0
                    if (textLength == 0) {
                        Log.d(TAG, "type_clear_text: field already empty on '$elementId'")
                        return McpToolUtils.textResult(
                            "Text cleared from element '$elementId'.\nField content: ",
                        )
                    }

                    // Select all text — check return value
                    if (!typeInputController.performContextMenuAction(android.R.id.selectAll)) {
                        throw McpToolException.ActionFailed(
                            "Failed to select all text in element '$elementId' — input connection lost",
                        )
                    }

                    // Delete selection via DELETE key event — check return values
                    if (!typeInputController.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
                        )
                    ) {
                        throw McpToolException.ActionFailed(
                            "Failed to send DELETE key (ACTION_DOWN) on element '$elementId' — input connection lost",
                        )
                    }
                    if (!typeInputController.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL),
                        )
                    ) {
                        throw McpToolException.ActionFailed(
                            "Failed to send DELETE key (ACTION_UP) on element '$elementId' — input connection lost",
                        )
                    }

                    // Read field content after operation for verification
                    readFieldContent(typeInputController)
                }

            Log.d(TAG, "type_clear_text: cleared text on element '$elementId'")
            return McpToolUtils.textResult(
                "Text cleared from element '$elementId'.\nField content: $fieldContent",
            )
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Clear all text from a field naturally using select-all + delete. " +
                        "Uses InputConnection operations (indistinguishable from user action). " +
                        "Returns the field content after the operation for verification.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Target element ID to clear")
                                }
                            },
                        required = listOf("element_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeClearTextTool"
            private const val TOOL_NAME = "type_clear_text"
        }
    }

/**
 * MCP tool: press_key
 *
 * Presses a specific key. Supported keys: ENTER, BACK, DEL, HOME, TAB, SPACE.
 *
 * Key mapping strategy:
 * - BACK, HOME: Delegate to ActionExecutor global actions (already implemented).
 * - ENTER: Use ACTION_IME_ENTER.
 * - DEL: Get current text from focused node, remove last character, set text.
 * - TAB, SPACE: Get current text from focused node, append character, set text.
 */
class PressKeyTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val key =
                arguments?.get("key")?.jsonPrimitive?.contentOrNull
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
            return McpToolUtils.textResult("Key '$upperKey' pressed successfully")
        }

        private fun pressEnter() {
            val focusedNode =
                findFocusedEditableNode(accessibilityServiceProvider)
                    ?: throw McpToolException.ElementNotFound(
                        "No focused element found for ENTER key",
                    )

            try {
                val success =
                    focusedNode.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
                    )
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
                findFocusedEditableNode(accessibilityServiceProvider)
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
                findFocusedEditableNode(accessibilityServiceProvider)
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

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Press a specific key (ENTER, BACK, DEL, HOME, TAB, SPACE)",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
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
                            },
                        required = listOf("key"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:PressKeyTool"
            private const val TOOL_NAME = "press_key"
            private val ALLOWED_KEYS = setOf("ENTER", "BACK", "DEL", "HOME", "TAB", "SPACE")
        }
    }

/**
 * Registers all text input tools with the given [Server].
 */
@Suppress("LongParameterList")
fun registerTextInputTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    typeInputController: TypeInputController,
    toolNamePrefix: String,
) {
    TypeAppendTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
        .register(server, toolNamePrefix)
    TypeInsertTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
        .register(server, toolNamePrefix)
    TypeReplaceTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
        .register(server, toolNamePrefix)
    TypeClearTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
        .register(server, toolNamePrefix)
    PressKeyTool(actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
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
@Suppress("ReturnCount", "MaxLineLength")
internal fun findFocusedEditableNode(accessibilityServiceProvider: AccessibilityServiceProvider): AccessibilityNodeInfo? {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service is not enabled",
        )
    }
    val rootNode = accessibilityServiceProvider.getRootNode() ?: return null
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
