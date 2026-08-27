@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshot
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.countKeptNodes
import com.danielealbano.androidremotecontrolmcp.services.accessibility.formatMultiWindowPage
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotRedactor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// get_screen_state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_screen_state`.
 *
 * Returns consolidated screen state: app metadata, screen info, and a compact
 * flat TSV-formatted list of UI elements. Optionally includes a low-resolution screenshot.
 *
 * Replaces: get_accessibility_tree, capture_screenshot, get_current_app, get_screen_info.
 */
class GetScreenStateHandler
    @Suppress("LongParameterList")
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val screenCaptureProvider: ScreenCaptureProvider,
        private val compactTreeFormatter: CompactTreeFormatter,
        private val screenshotAnnotator: ScreenshotAnnotator,
        private val screenshotEncoder: ScreenshotEncoder,
        private val nodeCache: AccessibilityNodeCache,
        private val screenStateSnapshotCache: ScreenStateSnapshotCache,
        private val webViewNodeMerger: WebViewNodeMerger,
        private val privacyToolGate: PrivacyToolGate,
        private val screenshotRedactor: ScreenshotRedactor,
    ) {
        @Volatile private var includeScreenshotEnabled: Boolean = true

        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val includeScreenshot = parseIncludeScreenshot(arguments)
            val cursorElement = arguments?.get("cursor")
            val fingerprintParam = arguments?.get("fingerprint")?.jsonPrimitive?.contentOrNull
            // Absent, JSON null, or a blank string ⇒ fresh cursorless capture (settled behavior 1).
            // A present, non-blank value (INCLUDING a non-primitive object/array) ⇒ paged path,
            // where an unusable value yields INVALID_CURSOR_MESSAGE guidance rather than throwing.
            val isFresh =
                cursorElement == null ||
                    cursorElement is JsonNull ||
                    ((cursorElement as? JsonPrimitive)?.contentOrNull?.isBlank() == true)
            return if (isFresh) {
                handleFreshRequest(includeScreenshot, fingerprintParam)
            } else {
                McpToolUtils.untrustedTextResult(buildPagedText(cursorElement, includeScreenshot))
            }
        }

        private fun parseIncludeScreenshot(arguments: JsonObject?): Boolean =
            if (includeScreenshotEnabled) {
                arguments?.get("include_screenshot")?.jsonPrimitive?.booleanOrNull ?: false
            } else {
                false
            }

        /**
         * Fresh (cursorless) capture with optional delta-gating via [fingerprintParam].
         *
         * When the client passes the `fingerprint` it received on the previous call and the
         * on-screen tree is unchanged, the response is a tiny "UNCHANGED" blob instead of a
         * full tree — the common case when polling after an action. The fingerprint is a
         * deterministic hash over the compact node rows, so any UI change (text, bounds,
         * visibility, flags, window set) produces a different fingerprint.
         */
        private suspend fun handleFreshRequest(
            includeScreenshot: Boolean,
            fingerprintParam: String?,
        ): CallToolResult {
            // getFreshWindows clears the framework accessibility cache before reading (see there),
            // so this fresh capture — and the node cache it populates for element/action tools —
            // round-trips live even for stale-prone WebView content.
            // The node cache (used by element/action tools) is populated by getFreshWindows from the
            // original tree; the merge only collapses the tree shown to the LLM. Merged anchors keep
            // their original ids, so taps still resolve.
            val rawResult =
                webViewNodeMerger.merge(getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache))
            // Redact the tree BEFORE anything downstream: the snapshot stores the REDACTED tree so paged
            // output and pseudonym mappings stay consistent. Fail-closed errors propagate as a tool error.
            val processed = privacyToolGate.tree(rawResult)
            val result = processed.result
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            val totalKept = compactTreeFormatter.countKeptNodes(result)
            val totalPages = ceilDiv(totalKept, CompactTreeFormatter.PAGE_SIZE)
            val fingerprint = computeFingerprint(result, screenInfo)
            // Delta gate: if the client's fingerprint matches the current tree, nothing changed.
            if (fingerprintParam != null && fingerprintParam == fingerprint) {
                Log.d(TAG, "get_screen_state: UNCHANGED (fingerprint match)")
                return McpToolUtils.untrustedTextResult(
                    buildJsonObject {
                        put("changed", false)
                        put("fingerprint", fingerprint)
                    }.let { Json.encodeToString(it) },
                )
            }
            val compactOutput = buildFreshPageText(result, screenInfo, totalKept, totalPages)
            Log.d(TAG, "get_screen_state: includeScreenshot=$includeScreenshot pages=$totalPages changed=true")
            return if (includeScreenshot) {
                buildScreenshotResult(result, screenInfo, compactOutput, processed.flaggedBounds, fingerprint)
            } else {
                McpToolUtils.untrustedTextResult(
                    buildJsonObject {
                        put("changed", true)
                        put("fingerprint", fingerprint)
                        put("state", compactOutput)
                    }.let { Json.encodeToString(it) },
                )
            }
        }

        /**
         * Deterministic fingerprint over the kept node rows of every window. Nodes are
         * sorted by (window id, node id) so row order never flips the hash between parses.
         */
        private fun computeFingerprint(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
        ): String {
            val rows = mutableListOf<String>()
            rows += "screen:${screenInfo.width}x${screenInfo.height}"
            for (window in result.windows) {
                rows += "win:${window.windowId}:${window.packageName}:${window.focused}"
                collectNodeRows(window.tree, rows)
            }
            rows.sort()
            return Integer.toHexString(rows.hashCode())
        }

        private fun collectNodeRows(
            node: AccessibilityNodeData,
            out: MutableList<String>,
        ) {
            out += nodeRow(node)
            for (child in node.children) {
                collectNodeRows(child, out)
            }
        }

        private fun nodeRow(node: AccessibilityNodeData): String {
            val b = node.bounds
            return listOf(
                node.id,
                node.className,
                node.text,
                node.contentDescription,
                node.resourceId,
                "${b.left},${b.top},${b.right},${b.bottom}",
                node.visible.toString(),
                node.clickable.toString(),
                node.editable.toString(),
            ).joinToString("\u0001")
        }

        private fun buildFreshPageText(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            totalKept: Int,
            totalPages: Int,
        ): String =
            if (totalPages <= 1) {
                screenStateSnapshotCache.clear()
                compactTreeFormatter.formatMultiWindow(result, screenInfo)
            } else {
                val snapshot =
                    ScreenStateSnapshot(
                        System.currentTimeMillis().toString(CURSOR_RADIX),
                        result,
                        screenInfo,
                        totalKept,
                        totalPages,
                    )
                screenStateSnapshotCache.store(snapshot)
                compactTreeFormatter.formatMultiWindowPage(snapshot, 1)
            }

        private fun buildPagedText(
            cursorElement: JsonElement?,
            includeScreenshot: Boolean,
        ): String {
            // Non-primitive (object/array) ⇒ contentOrNull is null ⇒ parsed is null ⇒ invalid-cursor guidance.
            val parsed = (cursorElement as? JsonPrimitive)?.contentOrNull?.let { parseCursor(it) }
            val snapshot = parsed?.let { screenStateSnapshotCache.get(it.first) }
            val body =
                when {
                    parsed == null -> {
                        INVALID_CURSOR_MESSAGE
                    }

                    snapshot == null -> {
                        SNAPSHOT_GONE_MESSAGE
                    }

                    parsed.second < 1 || parsed.second > snapshot.totalPages -> {
                        noSuchPageMessage(parsed.first, parsed.second, snapshot.totalPages)
                    }

                    else -> {
                        compactTreeFormatter.formatMultiWindowPage(snapshot, parsed.second)
                    }
                }
            // include_screenshot is ignored on ANY cursor call; when it was requested, append the
            // note to EVERY cursor response — valid page OR guidance — per agreed design point 9.
            return if (includeScreenshot) "$body\n$SCREENSHOT_ONLY_PAGE1_NOTE" else body
        }

        /**
         * Captures, annotates, and encodes the screenshot, returning a text+image result.
         *
         * NOTE: There is an inherent timing gap between tree parsing and screenshot capture.
         * If the UI changes in between, bounding boxes may reference stale element positions.
         * Atomic capture is not possible with the current Android accessibility APIs.
         */
        @Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")
        private suspend fun buildScreenshotResult(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            compactOutput: String,
            flaggedBounds: List<BoundsData>,
            fingerprint: String,
        ): CallToolResult {
            if (!screenCaptureProvider.isScreenCaptureAvailable()) {
                throw McpToolException.PermissionDenied(
                    "Screen capture not available. Please enable the accessibility " +
                        "service in Android Settings.",
                )
            }

            val bitmapResult =
                screenCaptureProvider.captureScreenshotBitmap(
                    maxWidth = SCREENSHOT_MAX_SIZE,
                    maxHeight = SCREENSHOT_MAX_SIZE,
                )
            val resizedBitmap =
                bitmapResult.getOrElse { exception ->
                    Log.e(TAG, "Screenshot capture failed", exception)
                    throw McpToolException.ActionFailed(
                        "Screenshot capture failed",
                    )
                }

            // Paint opaque boxes over flagged node bounds BEFORE annotation so PII never reaches the pixels.
            val maskedBitmap =
                screenshotRedactor.mask(resizedBitmap, flaggedBounds, screenInfo.width, screenInfo.height)
            var annotatedBitmap: Bitmap? = null
            try {
                // Collect on-screen elements from ALL windows' trees
                val onScreenElements = collectOnScreenElements(result.windows)

                // Annotate the (masked) screenshot with bounding boxes
                annotatedBitmap =
                    screenshotAnnotator.annotate(
                        maskedBitmap,
                        onScreenElements,
                        screenInfo.width,
                        screenInfo.height,
                    )

                // Encode annotated bitmap to base64 JPEG
                val screenshotData =
                    screenshotEncoder.bitmapToScreenshotData(
                        annotatedBitmap,
                        ScreenCaptureProvider.DEFAULT_QUALITY,
                    )

                return McpToolUtils.untrustedTextAndImageResult(
                    buildJsonObject {
                        put("changed", true)
                        put("fingerprint", fingerprint)
                        put("state", compactOutput)
                    }.let { Json.encodeToString(it) },
                    screenshotData.data,
                    "image/jpeg",
                )
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot annotation failed", e)
                throw McpToolException.ActionFailed(
                    "Screenshot annotation failed",
                )
            } finally {
                annotatedBitmap?.recycle()
                if (maskedBitmap !== resizedBitmap) maskedBitmap.recycle()
                resizedBitmap.recycle()
            }
        }

        /**
         * Collects elements from all windows that should be annotated on the screenshot:
         * nodes that pass the formatter's keep filter AND are visible (on-screen).
         */
        private fun collectOnScreenElements(windows: List<WindowData>): List<AccessibilityNodeData> {
            val result = mutableListOf<AccessibilityNodeData>()
            for (windowData in windows) {
                collectOnScreenElementsFromTree(windowData.tree, result)
            }
            return result
        }

        private fun collectOnScreenElementsFromTree(
            node: AccessibilityNodeData,
            result: MutableList<AccessibilityNodeData>,
        ) {
            if (compactTreeFormatter.shouldKeepNode(node) && node.visible) {
                result.add(node)
            }
            for (child in node.children) {
                collectOnScreenElementsFromTree(child, result)
            }
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
            includeScreenshotParamEnabled: Boolean = true,
        ) {
            includeScreenshotEnabled = includeScreenshotParamEnabled
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Returns the current screen state: app info, screen dimensions, " +
                        "and a compact UI node list (text/desc truncated to 100 chars, use " +
                        "${toolNamePrefix}get_node_details to retrieve full values). Optionally includes a " +
                        "low-resolution screenshot (only request the screenshot when the node " +
                        "list alone is not sufficient to understand the screen layout). " +
                        "Includes a hierarchy section showing node nesting via indentation. " +
                        "Large screens are split into pages of 200 nodes: the response includes a " +
                        "'page:N/total' line and a cursor; call again with that cursor to fetch the " +
                        "next page. You do NOT need to fetch every page — stop once you have found " +
                        "what you need. A screenshot can only be requested on page 1 (without a cursor).",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                if (includeScreenshotParamEnabled) {
                                    putJsonObject("include_screenshot") {
                                        put("type", "boolean")
                                        put(
                                            "description",
                                            "Include a low-resolution screenshot. " +
                                                "Only request when the UI node list is not sufficient.",
                                        )
                                        put("default", false)
                                    }
                                }
                                putJsonObject("cursor") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Pagination cursor from a previous response (format " +
                                            "\"<id>.<page>\"). Omit to capture a fresh screen state " +
                                            "starting at page 1. A cursor is tied to one screen " +
                                            "snapshot; if the screen changed you will be told to " +
                                            "request a fresh one.",
                                    )
                                }
                                putJsonObject("fingerprint") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Delta gate: pass the 'fingerprint' value from the previous " +
                                            "fresh (cursorless) response. If the on-screen tree is " +
                                            "unchanged the server returns {\"changed\":false, \"fingerprint\":...} " +
                                            "instead of a full tree — use this when polling to avoid " +
                                            "re-reading identical state.",
                                    )
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_screen_state"
            internal const val SCREENSHOT_MAX_SIZE = 700
            private const val TAG = "MCP:ScreenIntrospection"
            internal const val CURSOR_RADIX = 36
            internal const val INVALID_CURSOR_MESSAGE =
                "note:invalid cursor. Call get_screen_state without a cursor to get a fresh " +
                    "screen-state snapshot starting at page 1."
            internal const val SNAPSHOT_GONE_MESSAGE =
                "note:this screen-state snapshot is no longer available (the screen state was " +
                    "refreshed since this cursor was issued). Call get_screen_state without a " +
                    "cursor to get a fresh snapshot."
            internal const val SCREENSHOT_ONLY_PAGE1_NOTE =
                "note:a screenshot can only be requested on page 1 (call get_screen_state without " +
                    "a cursor / without specifying a page)."

            internal fun noSuchPageMessage(
                id: String,
                page: Int,
                totalPages: Int,
            ): String =
                "note:page $page does not exist — snapshot $id has $totalPages page(s). Call " +
                    "get_screen_state without a cursor for a fresh snapshot, or request a valid page."
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all screen introspection tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
@Suppress("LongParameterList")
fun registerScreenIntrospectionTools(
    registrar: LoggedToolRegistrar,
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    screenCaptureProvider: ScreenCaptureProvider,
    compactTreeFormatter: CompactTreeFormatter,
    screenshotAnnotator: ScreenshotAnnotator,
    screenshotEncoder: ScreenshotEncoder,
    nodeCache: AccessibilityNodeCache,
    screenStateSnapshotCache: ScreenStateSnapshotCache,
    webViewNodeMerger: WebViewNodeMerger,
    privacyToolGate: PrivacyToolGate,
    screenshotRedactor: ScreenshotRedactor,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetScreenStateHandler.TOOL_NAME)) {
        GetScreenStateHandler(
            treeParser,
            accessibilityServiceProvider,
            screenCaptureProvider,
            compactTreeFormatter,
            screenshotAnnotator,
            screenshotEncoder,
            nodeCache,
            screenStateSnapshotCache,
            webViewNodeMerger,
            privacyToolGate,
            screenshotRedactor,
        ).register(
            registrar,
            toolNamePrefix,
            includeScreenshotParamEnabled = perms.isParamEnabled(GetScreenStateHandler.TOOL_NAME, "include_screenshot"),
        )
    }
}

/**
 * Parses a pagination cursor "<id>.<page>" into (id, page). Validates FORMAT only (id present, page
 * parses as an integer); the page RANGE (1..totalPages) is validated by the caller, so that page < 1
 * yields the no-such-page guidance — NOT the invalid-cursor guidance. Returns null if malformed.
 */
private fun parseCursor(cursor: String): Pair<String, Int>? {
    val dot = cursor.lastIndexOf('.')
    val page = cursor.substringAfterLast('.', "").toIntOrNull()
    return if (dot <= 0 || dot == cursor.length - 1 || page == null) {
        null
    } else {
        cursor.substring(0, dot) to page
    }
}

private fun ceilDiv(
    a: Int,
    b: Int,
): Int = if (a <= 0) 1 else (a + b - 1) / b
