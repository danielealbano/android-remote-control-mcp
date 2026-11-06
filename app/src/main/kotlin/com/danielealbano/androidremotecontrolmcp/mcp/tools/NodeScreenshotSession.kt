package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.screencapture.NodeImageCropper
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotRedactor

/** What the node screenshot tool reads, scrolls and captures with. */
data class NodeScreenshotDeps(
    val treeParser: AccessibilityTreeParser,
    val accessibilityServiceProvider: AccessibilityServiceProvider,
    val nodeCache: AccessibilityNodeCache,
    val actionExecutor: ActionExecutor,
    val screenCaptureProvider: ScreenCaptureProvider,
    val screenshotRedactor: ScreenshotRedactor,
    val nodeImageCropper: NodeImageCropper,
    val webViewNodeMerger: WebViewNodeMerger,
    val privacyToolGate: PrivacyToolGate,
)

/**
 * The reply of one call: [text] (the scrolled note when the app was scrolled, then one line per requested id, without
 * the untrusted content warning) and the images in request order.
 */
internal class NodeScreenshots(
    val text: String,
    val images: List<String>,
    val captures: Int,
    val scrolled: Boolean,
)

/**
 * One `get_node_screenshots` call: one JPEG per requested node cropped from the screen, optionally scrolling the app
 * to capture nodes whole. The requested ids are resolved in the first read and followed through the later reads by
 * their live nodes. [read] reads the screen afresh, by default through the shared node cache.
 */
internal class NodeScreenshotSession(
    private val deps: NodeScreenshotDeps,
    private val read: () -> FreshRead = {
        getFreshRead(deps.treeParser, deps.accessibilityServiceProvider, deps.nodeCache)
    },
) {
    suspend fun run(
        nodeIds: List<String>,
        allowScroll: Boolean,
    ): NodeScreenshots {
        val scroller = ShotScroller(deps.actionExecutor, read)
        val capturer = ShotCapturer(deps)
        val first = scroller.readAgain()
        val targets =
            nodeIds.associateWith { id ->
                first.node(id)?.let { node -> first.data(id)?.let { ShotTarget(id, node, it) } }
            }
        val found = targets.values.filterNotNull()
        scroller.track(found)
        try {
            val context = ShotContext(deps, scroller, capturer)
            if (allowScroll) ShotRounds(context).run(found) else captureAsShown(context, found)
        } catch (e: McpToolException) {
            // Node ids have changed by then: whatever ends the call must say so.
            throw if (scroller.scrolled) e.withScrolledNote() else e
        }
        return reply(nodeIds, targets, scroller.scrolled, capturer.captures)
    }

    /** `allow_scroll` false: one read, one capture of the nodes on screen, cut ones marked partial. */
    private suspend fun captureAsShown(
        context: ShotContext,
        targets: List<ShotTarget>,
    ) {
        val shots =
            targets.mapNotNull { target ->
                when (val presence = target.presence(context.current)) {
                    is Presence.Gone -> {
                        target.outcome = ShotOutcome.NotCaptured(presence.reason.reason)
                        null
                    }

                    is Presence.Present -> {
                        shotAsShown(target, presence, judge(context.current, presence, null))
                    }
                }
            }
        if (shots.isNotEmpty()) context.captureEach(shots)
    }

    private fun shotAsShown(
        target: ShotTarget,
        present: Presence.Present,
        judgement: Judgement,
    ): Shot? =
        when (judgement) {
            Judgement.OffScreen -> {
                target.outcome = ShotOutcome.OffScreen
                null
            }

            Judgement.Whole -> {
                Shot(target, present.data.bounds, null)
            }

            is Judgement.Cut -> {
                Shot(target, present.data.bounds, judgement.wording)
            }
        }

    private fun reply(
        nodeIds: List<String>,
        targets: Map<String, ShotTarget?>,
        scrolled: Boolean,
        captures: Int,
    ): NodeScreenshots {
        val outcomes = nodeIds.map { id -> targets[id]?.let { checkNotNull(it.outcome) } ?: ShotOutcome.NotFound }
        var next = 1
        val lines =
            nodeIds.zip(outcomes) { id, outcome ->
                shotLine(id, outcome, next).also { next += outcome.images.size }
            }
        val text = (listOfNotNull(SCROLLED_NOTE.takeIf { scrolled }) + lines).joinToString("\n")
        return NodeScreenshots(text, outcomes.flatMap { it.images }, captures, scrolled)
    }
}

/** This error with the scrolled note appended, as the same subclass, the original as cause. */
internal fun McpToolException.withScrolledNote(): McpToolException {
    val text = "$message $SCROLLED_NOTE"
    return when (this) {
        is McpToolException.InvalidParams -> McpToolException.InvalidParams(text, this)
        is McpToolException.InternalError -> McpToolException.InternalError(text, this)
        is McpToolException.PermissionDenied -> McpToolException.PermissionDenied(text, this)
        is McpToolException.NodeNotFound -> McpToolException.NodeNotFound(text, this)
        is McpToolException.ActionFailed -> McpToolException.ActionFailed(text, this)
        is McpToolException.Timeout -> McpToolException.Timeout(text, this)
        is McpToolException.PrivacyModeUnavailable -> McpToolException.PrivacyModeUnavailable(text, this)
    }
}
