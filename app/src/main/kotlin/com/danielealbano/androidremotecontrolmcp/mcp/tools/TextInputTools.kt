@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeLock
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
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
private const val DEFAULT_TYPING_SPEED_MS = 250
private const val DEFAULT_TYPING_VARIANCE_MS = 50
private const val MIN_TYPING_SPEED_MS = 10
private const val MAX_TYPING_SPEED_MS = 5000
private const val MAX_SURROUNDING_TEXT_LENGTH = 10000
private const val FOCUS_POLL_INTERVAL_MS = 50L
private const val FOCUS_POLL_MAX_MS = 500L
private const val FIELD_UPDATE_POLL_MS = 50L
private const val FIELD_UPDATE_MAX_MS = 500L
private const val MAX_FIELD_READ_WINDOWS = 100
private const val COMMIT_VERIFY_RETRY_DELAY_MS = 50L
private const val COMMIT_MAX_RETRIES = 3
private const val ADAPTIVE_DELAY_INCREASE_MS = 50
private const val ADAPTIVE_DELAY_DECREASE_MS = 25
private const val ADAPTIVE_DELAY_MAX_MS = 2000

/**
 * Shared guidance appended to every text-entry tool description: entering text leaves the soft
 * keyboard open, which can cover lower parts of the screen. [toolNamePrefix] is interpolated so
 * the referenced tool name matches the server's configured prefix.
 */
private fun keyboardOverlayHint(toolNamePrefix: String): String =
    "Typing leaves the keyboard open and may cover elements; " +
        "call ${toolNamePrefix}dismiss_keyboard before tapping them."

/**
 * Standard trailing sentence shared by every text-entry tool description: confirms the
 * verification behavior and appends the [keyboardOverlayHint]. Kept as one helper so the common
 * tail stays DRY and does not lengthen each `register` function.
 */
private fun verificationAndKeyboardHint(toolNamePrefix: String): String =
    "Returns the field content after the operation for verification. " +
        keyboardOverlayHint(toolNamePrefix)

/**
 * Mutex serializing all type tool operations.
 * Prevents concurrent MCP requests from interleaving character commits.
 *
 * **Hold time**: The Mutex is held for the entire duration of a typing operation.
 * For 2000 chars at default 250ms: ~500 seconds. At max 5000ms: ~2.8 hours.
 * During this time, other type tool MCP requests are suspended (queued).
 * Non-type tools (tap, swipe, screenshot, etc.) are NOT blocked.
 */
internal val typeOperationMutex = Mutex()

/**
 * Types text code point by code point using the given [TypeInputController],
 * with per-character verification, retry logic, and adaptive pacing.
 *
 * Iterates by **Unicode code points** (not Char), so supplementary characters
 * (emoji, CJK extensions) are committed as a single unit instead of being
 * split into surrogate pairs.
 *
 * **Verification**: After each [TypeInputController.commitText], reads back via
 * [TypeInputController.getSurroundingText] (synchronous two-way IPC) to confirm
 * the character landed. On miss, waits [COMMIT_VERIFY_RETRY_DELAY_MS] and retries
 * (up to [COMMIT_MAX_RETRIES] retries). Before re-committing, a pre-retry length
 * check prevents double-commit when autocomplete/formatters alter text around
 * the cursor.
 *
 * **Adaptive pacing (AIMD-style)**: Each miss increases the effective inter-character
 * delay by [ADAPTIVE_DELAY_INCREASE_MS]. Each success decreases it by
 * [ADAPTIVE_DELAY_DECREASE_MS]. The delay is floored at [typingSpeed] and capped at
 * [ADAPTIVE_DELAY_MAX_MS]. Variance is applied on top of the effective delay.
 *
 * **IPC overhead**: Each character incurs at least one additional `getSurroundingText`
 * round-trip (verification). On retries, up to two additional calls per attempt
 * (verification + pre-retry length check). This is the accepted tradeoff for
 * reliable character delivery.
 *
 * Supports cancellation via structured concurrency — if the parent coroutine
 * is cancelled, the typing loop stops at the next `delay()` or retry wait.
 *
 * **Known limitation**: The `endsWith` verification check assumes the cursor stays
 * immediately after the committed character. Apps with auto-formatting that move the
 * cursor post-commit may cause spurious verification failures (mitigated by the
 * pre-retry length check).
 *
 * @param text The text to type.
 * @param typingSpeed Base delay between characters in ms (also the adaptive floor).
 * @param typingSpeedVariance Variance in ms.
 * @param typeInputController The input controller to commit characters through.
 * @throws McpToolException.ActionFailed if commitText fails, verification IC is lost,
 *   or a character is dropped after max retries.
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
    var effectiveDelay = typingSpeed

    // Read initial field length once for pre-retry length validation
    val initialFieldLength =
        typedLength(typeInputController.getSurroundingText(MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0))
    val ctx =
        TypingVerifyContext(
            typeInputController,
            codePointCount,
            typingSpeed,
            initialFieldLength,
        )

    while (offset < text.length) {
        val charCount = Character.charCount(text.codePointAt(offset))
        val codePointStr = text.substring(offset, offset + charCount)

        effectiveDelay =
            commitAndVerifyCodePoint(
                ctx,
                codePointStr,
                charCount,
                codePointIndex,
                effectiveDelay,
            )

        ctx.committedCodeUnits += charCount
        offset += charCount
        codePointIndex++

        // Skip delay after the last character
        if (offset < text.length) {
            delay(computeInterCharDelay(effectiveDelay, effectiveVariance))
        }
    }
}

/**
 * Bundles context for the commit-verify-retry loop, reducing parameter count.
 */
private class TypingVerifyContext(
    val controller: TypeInputController,
    val codePointCount: Int,
    val minDelay: Int,
    val initialFieldLength: Int?,
    var committedCodeUnits: Int = 0,
)

/**
 * Commits a single code point and verifies it landed. Retries up to [COMMIT_MAX_RETRIES]
 * times on verification miss. Adjusts effective delay via AIMD pacing.
 *
 * @return The updated effectiveDelay after this code point.
 */
private suspend fun commitAndVerifyCodePoint(
    ctx: TypingVerifyContext,
    codePointStr: String,
    charCount: Int,
    codePointIndex: Int,
    effectiveDelay: Int,
): Int {
    var currentDelay = effectiveDelay
    for (attempt in 0..COMMIT_MAX_RETRIES) {
        if (attempt == 0) {
            dispatchCommitText(ctx, codePointStr, codePointIndex)
        }

        val verified = verifyCommittedChar(ctx, charCount, codePointStr, codePointIndex)
        if (verified) {
            return (currentDelay - ADAPTIVE_DELAY_DECREASE_MS).coerceAtLeast(ctx.minDelay)
        }

        // Miss — increase pacing (capped)
        currentDelay = (currentDelay + ADAPTIVE_DELAY_INCREASE_MS).coerceAtMost(ADAPTIVE_DELAY_MAX_MS)
        if (attempt < COMMIT_MAX_RETRIES) {
            delay(COMMIT_VERIFY_RETRY_DELAY_MS)

            if (preRetryLengthCheckPassed(ctx, charCount)) {
                return (currentDelay - ADAPTIVE_DELAY_DECREASE_MS).coerceAtLeast(ctx.minDelay)
            }
            // Length didn't increase — char genuinely dropped, retry commit
            dispatchCommitText(ctx, codePointStr, codePointIndex)
        }
    }
    throw McpToolException.ActionFailed(
        "Character dropped after $COMMIT_MAX_RETRIES retries " +
            "at position $codePointIndex of ${ctx.codePointCount}.",
    )
}

private fun dispatchCommitText(
    ctx: TypingVerifyContext,
    codePointStr: String,
    codePointIndex: Int,
) {
    if (!ctx.controller.commitText(codePointStr, 1)) {
        throw McpToolException.ActionFailed(
            "Input connection lost during typing at position $codePointIndex of ${ctx.codePointCount}. " +
                "commitText returned false.",
        )
    }
}

private fun verifyCommittedChar(
    ctx: TypingVerifyContext,
    charCount: Int,
    codePointStr: String,
    codePointIndex: Int,
): Boolean {
    val surrounding =
        ctx.controller.getSurroundingText(charCount, 0, 0)
            ?: throw McpToolException.ActionFailed(
                "Input connection lost during verification at position $codePointIndex of ${ctx.codePointCount}.",
            )
    return surrounding.text.toString().endsWith(codePointStr)
}

/**
 * Whether the field grew by the character that failed the endsWith check, so it did land (an app moved the cursor).
 *
 * @throws McpToolException.ActionFailed when the growth cannot be measured (see [typedLength]): retrying could type
 *   the character twice.
 */
private fun preRetryLengthCheckPassed(
    ctx: TypingVerifyContext,
    charCount: Int,
): Boolean {
    val currentLength =
        typedLength(ctx.controller.getSurroundingText(MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0))
    val initialLength = ctx.initialFieldLength
    if (currentLength == null || initialLength == null) {
        throw McpToolException.ActionFailed(
            "Could not check whether a character landed: the field does not report where its text starts and its " +
                "cursor is too far in to measure it.",
        )
    }
    return currentLength >= initialLength + ctx.committedCodeUnits + charCount
}

/**
 * A length that grows by one for each character committed at the cursor, from a read around it: the window end when
 * the field reports where the window starts (an EditText), the window length when the window reaches the field start
 * (fewer than MAX_SURROUNDING_TEXT_LENGTH characters before the cursor), or null when neither holds (a field that
 * reports no offset, like a Compose text field, with the cursor further in) or the read failed.
 */
private fun typedLength(window: SurroundingText?): Int? =
    when {
        window == null -> null
        window.offset >= 0 -> window.offset + window.text.length
        window.selectionStart < MAX_SURROUNDING_TEXT_LENGTH -> window.text.length
        else -> null
    }

private fun computeInterCharDelay(
    effectiveDelay: Int,
    effectiveVariance: Int,
): Long =
    if (effectiveVariance > 0) {
        val variance = kotlin.random.Random.nextInt(-effectiveVariance, effectiveVariance + 1)
        (effectiveDelay + variance).coerceAtLeast(1).toLong()
    } else {
        effectiveDelay.toLong()
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
 * Polls until the input connection of the focused field answers, after the click and the focus check. Being ready
 * ([TypeInputController.isReady]) is not enough: right after input focus moves, the connection can still be the one
 * of the field that had focus before, which answers no read, so a read must succeed too.
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
 * @param nodeId The node ID (for error message).
 * @throws McpToolException.ActionFailed if not ready after FOCUS_POLL_MAX_MS.
 */
internal suspend fun awaitInputConnectionReady(
    typeInputController: TypeInputController,
    nodeId: String,
) {
    val deadline = System.currentTimeMillis() + FOCUS_POLL_MAX_MS
    while (System.currentTimeMillis() < deadline) {
        if (typeInputController.isReady() && typeInputController.getSurroundingText(1, 1, 0) != null) return
        delay(FOCUS_POLL_INTERVAL_MS)
    }
    throw McpToolException.ActionFailed(
        "Input connection not available after focusing node '$nodeId'. " +
            "The node may not be an editable text field.",
    )
}

/**
 * Waits until input focus is on the target [nodeId] or on an editable node inside it (a wrapper that hands focus to
 * an inner field) and returns the id of that focused node. A type tool types into, and reads its "Field content"
 * from, whichever field has input focus, so when focus stays on any other field it throws before anything is typed.
 *
 * A click does not always move input focus (in Chrome, clicking a web input through accessibility can leave it on the
 * previously focused input), so when focus is not there after FOCUS_POLL_MAX_MS the target's editable field (the
 * target itself, or the first editable node inside it) is given input focus with [ActionExecutor.focusNode] and the
 * wait runs once more.
 *
 * The focused node is matched against the nodes of [nodeId] and of its descendants in [readNodes], the node references
 * of the read [windows] come from (the one the target was resolved in; a target missing from it matches nothing), not
 * against the node cache: the cache is dropped once the window transition a click can start (the soft keyboard
 * showing) settles, which can happen during the wait. Before each search the editable ones among them are read again
 * ([AccessibilityNodeInfo.refresh]): Compose answers an input-focus search with the last node it built while that node
 * held focus, so a field the click focused after Compose last built its node is found only once that node is built
 * again. Polls like [awaitInputConnectionReady].
 *
 * @throws McpToolException.ActionFailed if focus is still not on the target or inside it after that.
 */
internal suspend fun focusedField(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    actionExecutor: ActionExecutor,
    readNodes: Map<String, CachedNode>,
    windows: List<WindowData>,
    nodeId: String,
): String {
    val target = windows.firstNotNullOfOrNull { findInTree(it.tree, nodeId) }
    val candidates = target?.let { focusCandidates(it, readNodes) }.orEmpty()
    var focused = pollFocusedCandidate(accessibilityServiceProvider, candidates)
    if (focused == null) {
        val field = target?.let(::firstEditable)?.id ?: nodeId
        if (actionExecutor.focusNode(field, windows).isSuccess) {
            focused = pollFocusedCandidate(accessibilityServiceProvider, candidates)
        }
    }
    return focused ?: throw McpToolException.ActionFailed(
        "Input focus is not on node '$nodeId' or on a field inside it, so nothing was typed. " +
            "Target the editable field itself.",
    )
}

/**
 * A node that can hold the input focus [focusedField] waits for: its [id] and its [node] from the read. When [field] is
 * true (the node was editable in the read; [findFocusedEditableNode] returns only editable nodes), [node] is read again
 * ([AccessibilityNodeInfo.refresh]) before each search.
 */
private class FocusCandidate(
    val id: String,
    val node: AccessibilityNodeInfo,
    val field: Boolean,
)

/** The candidates for input focus, in tree order: [target] and its descendants, with their nodes from [readNodes]. */
private fun focusCandidates(
    target: AccessibilityNodeData,
    readNodes: Map<String, CachedNode>,
): List<FocusCandidate> =
    subtreeNodes(target).mapNotNull { data ->
        readNodes[data.id]?.let { FocusCandidate(data.id, it.node, field = data.editable) }
    }

/** Polls up to FOCUS_POLL_MAX_MS for one of [candidates] to hold input focus; see [focusedCandidate]. */
private suspend fun pollFocusedCandidate(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    candidates: List<FocusCandidate>,
): String? {
    val deadline = System.currentTimeMillis() + FOCUS_POLL_MAX_MS
    var focused = focusedCandidate(accessibilityServiceProvider, candidates)
    while (focused == null && System.currentTimeMillis() < deadline) {
        delay(FOCUS_POLL_INTERVAL_MS)
        focused = focusedCandidate(accessibilityServiceProvider, candidates)
    }
    return focused
}

/** The id of the candidate that is the input-focused editable node, or null. */
private fun focusedCandidate(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    candidates: List<FocusCandidate>,
): String? {
    // Build the fields' nodes again first: Compose answers an input-focus search only with a node it built while that
    // node held focus
    candidates.filter { it.field }.forEach { it.node.refresh() }
    val focusedNode = findFocusedEditableNode(accessibilityServiceProvider) ?: return null
    val match = candidates.firstOrNull { it.node == focusedNode }?.id
    @Suppress("DEPRECATION")
    focusedNode.recycle()
    return match
}

/** [target] followed by its descendants, depth first. */
private fun subtreeNodes(target: AccessibilityNodeData): List<AccessibilityNodeData> {
    val nodes = ArrayList<AccessibilityNodeData>()
    collectNodes(target, nodes)
    return nodes
}

/** [node] when it is editable, else the first editable node inside it (depth first), else null. */
private fun firstEditable(node: AccessibilityNodeData): AccessibilityNodeData? =
    if (node.editable) node else node.children.firstNotNullOfOrNull(::firstEditable)

private fun findInTree(
    node: AccessibilityNodeData,
    nodeId: String,
): AccessibilityNodeData? {
    if (node.id == nodeId) return node
    return node.children.firstNotNullOfOrNull { findInTree(it, nodeId) }
}

private fun collectNodes(
    node: AccessibilityNodeData,
    out: MutableList<AccessibilityNodeData>,
) {
    out += node
    node.children.forEach { collectNodes(it, out) }
}

/**
 * The whole text of the focused field. When one read around the cursor covers the field (fewer than
 * MAX_SURROUNDING_TEXT_LENGTH characters on each side of it, the usual case) that read is the text, whether or not the
 * field reports offsets (a Compose text field reports none). A longer field is read window by window from positions the
 * tool sets itself, which moves the cursor: it is put back at [restoreCursor] when given.
 *
 * @return the text, or null when the input connection does not answer.
 */
internal fun readWholeField(
    typeInputController: TypeInputController,
    restoreCursor: Int? = null,
): String? {
    val window = typeInputController.getSurroundingText(MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0)
    return when {
        window == null -> {
            null
        }

        coversField(window) -> {
            window.text.toString()
        }

        else -> {
            readFieldByWindows(typeInputController)?.takeIf {
                restoreCursor == null || typeInputController.setSelection(restoreCursor, restoreCursor)
            }
        }
    }
}

/** Whether [window], read with MAX_SURROUNDING_TEXT_LENGTH asked on each side of the cursor, holds the whole field. */
private fun coversField(window: SurroundingText): Boolean =
    window.selectionStart < MAX_SURROUNDING_TEXT_LENGTH &&
        window.text.length - window.selectionEnd < MAX_SURROUNDING_TEXT_LENGTH

/**
 * The whole text of the focused field (see [readWholeField]).
 *
 * @throws McpToolException.ActionFailed when the input connection does not answer.
 */
internal fun readFieldText(
    typeInputController: TypeInputController,
    nodeId: String,
): String =
    readWholeField(typeInputController)
        ?: throw McpToolException.ActionFailed("Unable to read text from node '$nodeId'")

/**
 * Reads the field from its start: puts the cursor at each position and takes the MAX_SURROUNDING_TEXT_LENGTH
 * characters after it, until a shorter piece. Null when the connection does not answer, does not put the cursor where
 * it was asked to, or the field is longer than MAX_FIELD_READ_WINDOWS windows.
 */
private fun readFieldByWindows(typeInputController: TypeInputController): String? {
    val text = StringBuilder()
    var complete = false
    var failed = false
    var windows = 0
    while (!complete && !failed && windows < MAX_FIELD_READ_WINDOWS) {
        val piece = readPieceAt(typeInputController, text.length)
        if (piece == null) {
            failed = true
        } else {
            text.append(piece)
            complete = piece.length < MAX_SURROUNDING_TEXT_LENGTH
        }
        windows++
    }
    return if (complete) text.toString() else null
}

/**
 * The up to MAX_SURROUNDING_TEXT_LENGTH characters after [position], read with the cursor put there; null when the
 * connection does not answer, or a field that reports offsets shows the cursor somewhere else.
 */
private fun readPieceAt(
    typeInputController: TypeInputController,
    position: Int,
): String? {
    val window =
        if (typeInputController.setSelection(position, position)) {
            typeInputController.getSurroundingText(0, MAX_SURROUNDING_TEXT_LENGTH, 0)
        } else {
            null
        }
    return window
        ?.takeIf { it.offset < 0 || it.offset + it.selectionStart == position }
        ?.let { it.text.substring(it.selectionEnd) }
}

/**
 * Deletes the current selection by committing empty text through the input connection, then waits up to
 * FIELD_UPDATE_MAX_MS until the field reads back different from [before], its text when the selection was made. A DEL
 * key event is not used: it goes through the view's key handling instead of the input connection's edit pipeline that
 * set the selection, and Compose text fields can handle it before that selection and lose the deletion. Any change
 * counts, so a field that reformats its text after the deletion is not taken for a lost one.
 *
 * @return the field text after the deletion. It can move the cursor (see [readWholeField]).
 * @throws McpToolException.ActionFailed when the input connection is lost or the field reads back unchanged.
 */
internal suspend fun deleteSelection(
    typeInputController: TypeInputController,
    before: String,
    nodeId: String,
): String {
    commitEmptyText(typeInputController, nodeId)
    val deadline = System.currentTimeMillis() + FIELD_UPDATE_MAX_MS
    var after = readWholeField(typeInputController)
    while ((after == null || after == before) && System.currentTimeMillis() < deadline) {
        delay(FIELD_UPDATE_POLL_MS)
        after = readWholeField(typeInputController)
    }
    return when (after) {
        null -> {
            throw McpToolException.ActionFailed(
                "Could not read node '$nodeId' back after deleting the selected text — input connection lost",
            )
        }

        before -> {
            throw McpToolException.ActionFailed(
                "The selected text in node '$nodeId' was not deleted: the field reads back unchanged",
            )
        }

        else -> {
            after
        }
    }
}

/** Commits empty text over the selection, which deletes it. */
private fun commitEmptyText(
    typeInputController: TypeInputController,
    nodeId: String,
) {
    if (!typeInputController.commitText("", 1)) {
        throw McpToolException.ActionFailed(
            "Failed to delete the selected text in node '$nodeId' — input connection lost",
        )
    }
}

/**
 * Reads the field content after an operation completes, to include it in the tool response so the model can verify
 * the result; [cursor] is where the operation left the cursor (see [readWholeField]).
 *
 * Returns the field text, or null if the content could not be read (e.g., input connection lost after the
 * operation). This is best-effort — a read failure does NOT cause the tool to fail,
 * since the operation itself already succeeded.
 *
 * @param typeInputController The controller to read from.
 * @return The field content, or null when it could not be read.
 */
internal fun readFieldContent(
    typeInputController: TypeInputController,
    cursor: Int,
): String? = readWholeField(typeInputController, restoreCursor = cursor)

/**
 * What a type tool read back from the field it typed into, kept for the redaction that runs outside the typing lock:
 * the [content] (null when it could not be read), the focused field [fieldNodeId] and the [windows] its context comes
 * from, and whether its editor is a password input.
 */
internal data class FieldRead(
    val content: String?,
    val fieldNodeId: String,
    val isPasswordInput: Boolean,
    val windows: List<WindowData>,
)

/** Reads back the focused field [fieldNodeId] after an operation that left the cursor at [cursor]; see [FieldRead]. */
internal fun readField(
    typeInputController: TypeInputController,
    fieldNodeId: String,
    windows: List<WindowData>,
    cursor: Int,
): FieldRead {
    val content = readFieldContent(typeInputController, cursor)
    return FieldRead(content, fieldNodeId, typeInputController.isPasswordInput(), windows)
}

/**
 * The "Field content" of a type tool response: [read]'s content redacted with the focused field's own context, as a
 * password when its editor is a password input, or [FIELD_CONTENT_UNREADABLE] when the field could not be read (a
 * server string, never redacted).
 */
internal suspend fun responseFieldContent(
    privacyToolGate: PrivacyToolGate,
    read: FieldRead,
): String =
    read.content?.let { privacyToolGate.nodeText(read.windows, it, read.fieldNodeId, read.isPasswordInput) }
        ?: FIELD_CONTENT_UNREADABLE

internal const val FIELD_CONTENT_UNREADABLE = "(unable to read field content)"

class TypeAppendTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
        private val targetResolver: NodeTargetResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            // Reverse any pseudonym placeholder so the real value is typed; length checks use the real length.
            val text = substitutor.substitute(McpToolUtils.requireString(arguments, "text"))
            if (text.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'text' must be non-empty")
            }
            validateTextLength(text)

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            val (nodeId, field) =
                typeOperationMutex.withLock {
                    // Resolve the target in a fresh read, then click to focus
                    val (result, readNodes) = getFreshRead(treeParser, accessibilityServiceProvider, nodeCache)
                    val nodeId =
                        when (val resolution = targetResolver.resolve(target, result.windows)) {
                            is NodeResolution.Resolved -> resolution.nodeId
                            is NodeResolution.Ambiguous -> return resolution.result
                        }
                    val clickResult = actionExecutor.clickNode(nodeId, result.windows)
                    clickResult.onFailure { e -> mapNodeActionException(e, nodeId) }

                    // Wait for input focus on the target, then for its InputConnection (max 500ms each, 50ms interval)
                    val fieldNodeId =
                        focusedField(accessibilityServiceProvider, actionExecutor, readNodes, result.windows, nodeId)
                    awaitInputConnectionReady(typeInputController, nodeId)

                    // Position cursor at end
                    val textLength = readFieldText(typeInputController, nodeId).length
                    if (!typeInputController.setSelection(textLength, textLength)) {
                        throw McpToolException.ActionFailed(
                            "Failed to position cursor in node '$nodeId' — input connection lost",
                        )
                    }

                    // Type code point by code point
                    typeCharByChar(text, typingSpeed, typingSpeedVariance, typeInputController)

                    // Read field content after operation for verification
                    nodeId to readField(typeInputController, fieldNodeId, result.windows, textLength + text.length)
                }

            Log.d(TAG, "type_append_text: typed ${text.length} chars on node '$nodeId'")
            val redactedFieldContent = responseFieldContent(privacyToolGate, field)
            return McpToolUtils.untrustedTextResult(
                "Typed ${text.length} characters at end of node '$nodeId'.\n" +
                    "Field content: $redactedFieldContent",
            )
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Type text character by character at the end of a text field. " +
                        "Uses natural InputConnection typing (indistinguishable from keyboard input). " +
                        "Maximum text length: $MAX_TEXT_LENGTH characters. " +
                        "For text longer than $MAX_TEXT_LENGTH chars, call this tool multiple times — " +
                        "subsequent calls continue typing at the current cursor position. " +
                        verificationAndKeyboardHint(toolNamePrefix),
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
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
                        required = listOf("text"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeAppendTextTool"
            const val TOOL_NAME = "type_append_text"
        }
    }

class TypeInsertTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
        private val targetResolver: NodeTargetResolver,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            val text = substitutor.substitute(McpToolUtils.requireString(arguments, "text"))
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

            val (nodeId, field) =
                typeOperationMutex.withLock {
                    // Resolve the target in a fresh read, then click to focus
                    val (result, readNodes) = getFreshRead(treeParser, accessibilityServiceProvider, nodeCache)
                    val nodeId =
                        when (val resolution = targetResolver.resolve(target, result.windows)) {
                            is NodeResolution.Resolved -> resolution.nodeId
                            is NodeResolution.Ambiguous -> return resolution.result
                        }
                    val clickResult = actionExecutor.clickNode(nodeId, result.windows)
                    clickResult.onFailure { e -> mapNodeActionException(e, nodeId) }

                    // Wait for input focus on the target, then for its InputConnection
                    val fieldNodeId =
                        focusedField(accessibilityServiceProvider, actionExecutor, readNodes, result.windows, nodeId)
                    awaitInputConnectionReady(typeInputController, nodeId)

                    // Validate offset against current text length
                    val textLength = readFieldText(typeInputController, nodeId).length
                    if (offset > textLength) {
                        throw McpToolException.InvalidParams(
                            "offset ($offset) exceeds text length ($textLength) in node '$nodeId'",
                        )
                    }

                    // Position cursor at offset — check return value
                    if (!typeInputController.setSelection(offset, offset)) {
                        throw McpToolException.ActionFailed(
                            "Failed to position cursor at offset $offset " +
                                "in node '$nodeId' — input connection lost",
                        )
                    }

                    // Type code point by code point
                    typeCharByChar(text, typingSpeed, typingSpeedVariance, typeInputController)

                    // Read field content after operation for verification
                    nodeId to readField(typeInputController, fieldNodeId, result.windows, offset + text.length)
                }

            Log.d(TAG, "type_insert_text: typed ${text.length} chars at offset $offset on '$nodeId'")
            val redactedFieldContent = responseFieldContent(privacyToolGate, field)
            return McpToolUtils.untrustedTextResult(
                "Typed ${text.length} characters at offset $offset in node '$nodeId'.\n" +
                    "Field content: $redactedFieldContent",
            )
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Type text character by character at a specific position in a text field. " +
                        "Uses natural InputConnection typing (indistinguishable from keyboard input). " +
                        "Maximum text length: $MAX_TEXT_LENGTH characters. " +
                        verificationAndKeyboardHint(toolNamePrefix),
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
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
                                        "0-based character offset (UTF-16 code units) " +
                                            "for cursor position. Must be within " +
                                            "[0, current text length]. Note: emoji " +
                                            "and other supplementary characters count " +
                                            "as 2 units.",
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
                        required = listOf("text", "offset"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeInsertTextTool"
            const val TOOL_NAME = "type_insert_text"
        }
    }

/**
 * MCP tool: type_replace_text
 *
 * Finds the first occurrence of search text in a field, selects it,
 * deletes it by committing empty text over the selection (see [deleteSelection]),
 * then types the replacement text code point by code point using the InputConnection pipeline.
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
 * 1. Resolve the target (node_id or selector) in a fresh read and click it to focus
 * 2. Get the whole field text (see [readWholeField])
 * 3. Find first occurrence of search text — error if not found
 * 4. setSelection(startIndex, endIndex) to select found text
 * 5. Commit empty text over the selection and wait until the field reads back changed
 * 6. Type new_text code point by code point via commitText() (if non-empty)
 */
class TypeReplaceTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
        private val targetResolver: NodeTargetResolver,
    ) {
        @Suppress("ThrowsCount", "LongMethod")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            // Substitute both the search term (to match the real field content) and the replacement value.
            val search = substitutor.substitute(McpToolUtils.requireString(arguments, "search"))
            if (search.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'search' must be non-empty")
            }
            // Validate search length — bounded by getSurroundingText buffer
            if (search.length > MAX_SURROUNDING_TEXT_LENGTH) {
                throw McpToolException.InvalidParams(
                    "search exceeds maximum length of $MAX_SURROUNDING_TEXT_LENGTH characters (got ${search.length})",
                )
            }

            val newText = substitutor.substitute(McpToolUtils.requireString(arguments, "new_text"))
            if (newText.isNotEmpty()) {
                validateTextLength(newText, "new_text")
            }

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            val (nodeId, field) =
                typeOperationMutex.withLock {
                    // Resolve the target in a fresh read, then click to focus
                    val (result, readNodes) = getFreshRead(treeParser, accessibilityServiceProvider, nodeCache)
                    val nodeId =
                        when (val resolution = targetResolver.resolve(target, result.windows)) {
                            is NodeResolution.Resolved -> resolution.nodeId
                            is NodeResolution.Ambiguous -> return resolution.result
                        }
                    val clickResult = actionExecutor.clickNode(nodeId, result.windows)
                    clickResult.onFailure { e -> mapNodeActionException(e, nodeId) }

                    // Wait for input focus on the target, then for its InputConnection
                    val fieldNodeId =
                        focusedField(accessibilityServiceProvider, actionExecutor, readNodes, result.windows, nodeId)
                    awaitInputConnectionReady(typeInputController, nodeId)

                    // Get the whole text and find the search string
                    val fullText = readFieldText(typeInputController, nodeId)
                    val searchIndex = fullText.indexOf(search)
                    if (searchIndex == -1) {
                        throw McpToolException.NodeNotFound(
                            "Search text (${search.length} chars) not found in node '$nodeId'",
                        )
                    }

                    // Select the found text — check return value
                    if (!typeInputController.setSelection(searchIndex, searchIndex + search.length)) {
                        throw McpToolException.ActionFailed(
                            "Failed to select text in node '$nodeId' — input connection lost",
                        )
                    }

                    // Delete the selection, then put the cursor where it was: the read-back can move it, and a field
                    // that reformats its text after the deletion can be shorter than the found position
                    val afterDeletion = deleteSelection(typeInputController, fullText, nodeId)
                    val cursor = minOf(searchIndex, afterDeletion.length)
                    if (!typeInputController.setSelection(cursor, cursor)) {
                        throw McpToolException.ActionFailed(
                            "Failed to position cursor in node '$nodeId' — input connection lost",
                        )
                    }

                    // Type replacement text code point by code point (if non-empty)
                    if (newText.isNotEmpty()) {
                        typeCharByChar(newText, typingSpeed, typingSpeedVariance, typeInputController)
                    }

                    // Read field content after operation for verification
                    nodeId to readField(typeInputController, fieldNodeId, result.windows, cursor + newText.length)
                }

            Log.d(
                TAG,
                "type_replace_text: replaced ${search.length} chars " +
                    "with ${newText.length} chars on '$nodeId'",
            )
            val redactedFieldContent = responseFieldContent(privacyToolGate, field)
            return McpToolUtils.untrustedTextResult(
                "Replaced ${search.length} characters with ${newText.length} characters in node '$nodeId'.\n" +
                    "Field content: $redactedFieldContent",
            )
        }

        @Suppress("LongMethod")
        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Find and replace text in a field by typing the replacement naturally. " +
                        "Finds the first occurrence of search text, deletes it, then types new_text " +
                        "character by character via InputConnection. " +
                        "Maximum new_text length: $MAX_TEXT_LENGTH characters. " +
                        "Returns error if search text is not found. " +
                        verificationAndKeyboardHint(toolNamePrefix),
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
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
                        required = listOf("search", "new_text"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeReplaceTextTool"
            const val TOOL_NAME = "type_replace_text"
        }
    }

/**
 * MCP tool: type_clear_text
 *
 * Clears all text from a field using InputConnection operations: select all via the context menu action, then
 * commit empty text over the selection (see [deleteSelection]) and wait until the field reads back changed, failing
 * when it does not.
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
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
        private val targetResolver: NodeTargetResolver,
    ) {
        /** What happened under the typing lock. */
        private sealed interface ClearOutcome {
            data class Ambiguous(
                val result: CallToolResult,
            ) : ClearOutcome

            data class AlreadyEmpty(
                val nodeId: String,
            ) : ClearOutcome

            data class Cleared(
                val nodeId: String,
                val field: FieldRead,
            ) : ClearOutcome
        }

        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            return when (val outcome = typeOperationMutex.withLock { clearLocked(target) }) {
                is ClearOutcome.Ambiguous -> {
                    outcome.result
                }

                is ClearOutcome.AlreadyEmpty -> {
                    McpToolUtils.untrustedTextResult("Text cleared from node '${outcome.nodeId}'.\nField content: ")
                }

                is ClearOutcome.Cleared -> {
                    Log.d(TAG, "type_clear_text: cleared text on node '${outcome.nodeId}'")
                    val redactedFieldContent = responseFieldContent(privacyToolGate, outcome.field)
                    McpToolUtils.untrustedTextResult(
                        "Text cleared from node '${outcome.nodeId}'.\nField content: $redactedFieldContent",
                    )
                }
            }
        }

        /** Resolves the target in a fresh read, focuses it and clears it; runs under [typeOperationMutex]. */
        private suspend fun clearLocked(target: NodeTarget): ClearOutcome {
            val (result, readNodes) = getFreshRead(treeParser, accessibilityServiceProvider, nodeCache)
            val nodeId =
                when (val resolution = targetResolver.resolve(target, result.windows)) {
                    is NodeResolution.Resolved -> resolution.nodeId
                    is NodeResolution.Ambiguous -> return ClearOutcome.Ambiguous(resolution.result)
                }
            val clickResult = actionExecutor.clickNode(nodeId, result.windows)
            clickResult.onFailure { e -> mapNodeActionException(e, nodeId) }

            // Wait for input focus on the target, then for its InputConnection
            val fieldNodeId =
                focusedField(accessibilityServiceProvider, actionExecutor, readNodes, result.windows, nodeId)
            awaitInputConnectionReady(typeInputController, nodeId)

            // Check if field has text — skip clear if already empty
            val text = readFieldText(typeInputController, nodeId)
            return if (text.isEmpty()) {
                Log.d(TAG, "type_clear_text: field already empty on '$nodeId'")
                ClearOutcome.AlreadyEmpty(nodeId)
            } else {
                // Select all text — check return value
                requireInputStep(
                    typeInputController.performContextMenuAction(android.R.id.selectAll),
                    "Failed to select all text in node '$nodeId' — input connection lost",
                )

                // Delete the selection and wait until the field reads back changed
                deleteSelection(typeInputController, text, nodeId)

                // Read field content after operation for verification
                ClearOutcome.Cleared(nodeId, readField(typeInputController, fieldNodeId, result.windows, 0))
            }
        }

        /** Fails the tool with [failure] when an input step did not succeed. */
        private fun requireInputStep(
            succeeded: Boolean,
            failure: String,
        ) {
            if (!succeeded) throw McpToolException.ActionFailed(failure)
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Clear all text from a field with select-all and delete through the InputConnection, " +
                        "like an input method, then check that the text is gone. " +
                        verificationAndKeyboardHint(toolNamePrefix),
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeClearTextTool"
            const val TOOL_NAME = "type_clear_text"
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

                "ENTER" -> {
                    pressEnter()
                }

                "DEL" -> {
                    pressDelete()
                }

                "TAB" -> {
                    appendCharToFocused('\t')
                }

                "SPACE" -> {
                    appendCharToFocused(' ')
                }
            }

            Log.d(TAG, "press_key: key=$upperKey succeeded")
            return McpToolUtils.textResult("Key '$upperKey' pressed successfully")
        }

        private fun pressEnter() {
            val focusedNode =
                findFocusedEditableNode(accessibilityServiceProvider)
                    ?: throw McpToolException.NodeNotFound(
                        "No focused node found for ENTER key",
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
                    ?: throw McpToolException.NodeNotFound(
                        "No focused editable node found for DEL key",
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
                    ?: throw McpToolException.NodeNotFound(
                        "No focused editable node found for key input",
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
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
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
            const val TOOL_NAME = "press_key"
            private val ALLOWED_KEYS = setOf("ENTER", "BACK", "DEL", "HOME", "TAB", "SPACE")
        }
    }

/**
 * Registers all text input tools with the given [Server].
 */
@Suppress("LongParameterList")
fun registerTextInputTools(
    registrar: LoggedToolRegistrar,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    typeInputController: TypeInputController,
    nodeCache: AccessibilityNodeCache,
    privacyToolGate: PrivacyToolGate,
    substitutor: PlaceholderSubstitutor,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    val targetResolver = NodeTargetResolver(elementFinder, privacyToolGate, substitutor)
    if (perms.isToolEnabled(TypeAppendTextTool.TOOL_NAME)) {
        TypeAppendTextTool(
            treeParser,
            actionExecutor,
            accessibilityServiceProvider,
            typeInputController,
            nodeCache,
            privacyToolGate,
            substitutor,
            targetResolver,
        ).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(TypeInsertTextTool.TOOL_NAME)) {
        TypeInsertTextTool(
            treeParser,
            actionExecutor,
            accessibilityServiceProvider,
            typeInputController,
            nodeCache,
            privacyToolGate,
            substitutor,
            targetResolver,
        ).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(TypeReplaceTextTool.TOOL_NAME)) {
        TypeReplaceTextTool(
            treeParser,
            actionExecutor,
            accessibilityServiceProvider,
            typeInputController,
            nodeCache,
            privacyToolGate,
            substitutor,
            targetResolver,
        ).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(TypeClearTextTool.TOOL_NAME)) {
        TypeClearTextTool(
            treeParser,
            actionExecutor,
            accessibilityServiceProvider,
            typeInputController,
            nodeCache,
            privacyToolGate,
            targetResolver,
        ).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(PressKeyTool.TOOL_NAME)) {
        PressKeyTool(actionExecutor, accessibilityServiceProvider).register(registrar, toolNamePrefix)
    }
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
@Suppress("MaxLineLength")
internal fun findFocusedEditableNode(accessibilityServiceProvider: AccessibilityServiceProvider): AccessibilityNodeInfo? =
    // Serialize the clear+read critical section against concurrent MCP requests (see
    // AccessibilityTreeLock): a parallel request's cache clear must not wipe the framework tree
    // mid-traversal of this focused-node lookup.
    synchronized(AccessibilityTreeLock.monitor) {
        findFocusedEditableNodeLocked(accessibilityServiceProvider)
    }

@Suppress(
    "ReturnCount",
    "NestedBlockDepth",
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MaxLineLength",
)
private fun findFocusedEditableNodeLocked(accessibilityServiceProvider: AccessibilityServiceProvider): AccessibilityNodeInfo? {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service is not enabled",
        )
    }

    // Drop the framework's accessibility node cache before this live read. Chromium WebView
    // suppresses/throttles the content-changed events that invalidate that cache, so the focused
    // node's text could be stale — and DEL/TAB/SPACE (which read focusedNode.text to build the
    // ACTION_SET_TEXT payload) would then edit off a stale value, corrupting a WebView input whose
    // value changed via JavaScript. See AccessibilityServiceProvider.clearFrameworkNodeCache.
    accessibilityServiceProvider.clearFrameworkNodeCache()

    // Search across all windows for the focused editable node.
    // Uses a found-node variable to avoid returning from inside the try/finally block,
    // ensuring all AccessibilityWindowInfo objects are properly recycled before the
    // caller receives the result.
    val windows = accessibilityServiceProvider.getAccessibilityWindows()
    var foundNode: AccessibilityNodeInfo? = null
    try {
        for (window in windows) {
            val rootNode = window.root ?: continue
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null && focusedNode.isEditable) {
                // Recycle root AFTER confirming focusedNode is valid and editable.
                // On API 34+ recycle() is a no-op, but ordering is correct for safety.
                @Suppress("DEPRECATION")
                rootNode.recycle()
                foundNode = focusedNode
                break
            }

            // Not the node we want — recycle both
            @Suppress("DEPRECATION")
            rootNode.recycle()
            if (focusedNode != null) {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            }
        }
    } finally {
        // Recycle all AccessibilityWindowInfo objects for consistency
        // (no-op on API 34+, but follows the codebase's recycling convention)
        for (w in windows) {
            @Suppress("DEPRECATION")
            w.recycle()
        }
    }

    if (foundNode != null) return foundNode

    // Fallback: try rootInActiveWindow if getWindows() returned empty
    if (windows.isEmpty()) {
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

    return null
}
