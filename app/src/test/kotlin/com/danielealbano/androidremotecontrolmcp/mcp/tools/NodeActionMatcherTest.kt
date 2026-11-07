package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NodeActionMatcher")
class NodeActionMatcherTest {
    private val bounds = BoundsData(0, 0, 100, 100)

    /** A web node with actions, under a native host and a web root: the merge keeps it as an anchor. */
    private val webNode =
        AccessibilityNodeData(
            id = "node_web",
            contentDescription = "Contact card",
            bounds = bounds,
            visible = true,
            webRole = "genericContainer",
            actions = listOf("Call Alice", "Share"),
        )

    private val nativeNode = webNode.copy(id = "node_native", webRole = null)

    private fun resultOf(node: AccessibilityNodeData): MultiWindowResult {
        val webRoot =
            AccessibilityNodeData(
                id = "node_webroot",
                bounds = bounds,
                webRole = "rootWebArea",
                children = listOf(node),
            )
        val host = AccessibilityNodeData(id = "node_host", bounds = bounds, children = listOf(webRoot))
        return MultiWindowResult(windows = listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = host)))
    }

    /**
     * A gate that redacts "Alice" as `[RAW]` in the windows as read ([raw], compared by identity) and as `[MERGED]`
     * anywhere else, as a context-dependent detection would in the WebView-merged tree.
     */
    private fun gateFor(raw: MultiWindowResult): PrivacyToolGate {
        val gate = mockk<PrivacyToolGate>()
        coEvery { gate.nodeTexts(any(), any()) } answers {
            val placeholder = if (firstArg<List<WindowData>>() === raw.windows) "[RAW]" else "[MERGED]"
            secondArg<List<Pair<String?, String>>>().map { (text, _) -> text?.replace("Alice", placeholder) }
        }
        return gate
    }

    @Test
    fun `shownLabels are the labels redacted in the windows as read, in order`() =
        runTest {
            val result = resultOf(webNode)
            val matcher = NodeActionMatcher(gateFor(result), WebViewNodeMerger())

            assertEquals(listOf("Call [RAW]", "Share"), matcher.shownLabels(result, webNode))
        }

    @Test
    fun `a web node's label matches as get_screen_state shows it, from the merged tree`() =
        runTest {
            val result = resultOf(webNode)
            val matcher = NodeActionMatcher(gateFor(result), WebViewNodeMerger())
            val shown = matcher.shownLabels(result, webNode)

            assertEquals(0, matcher.indexOf(result, webNode, shown, "Call [RAW]"))
            assertEquals(0, matcher.indexOf(result, webNode, shown, "Call [MERGED]"))
            assertEquals(1, matcher.indexOf(result, webNode, shown, "Share"))
            assertEquals(-1, matcher.indexOf(result, webNode, shown, "Call Alice"))
        }

    @Test
    fun `a native node on a screen with web content also matches in the merged form`() =
        runTest {
            val webText = AccessibilityNodeData(id = "node_text", text = "Hi", bounds = bounds, webRole = "staticText")
            val webArea =
                AccessibilityNodeData(
                    id = "node_webroot",
                    bounds = bounds,
                    webRole = "rootWebArea",
                    children = listOf(webText),
                )
            val host = AccessibilityNodeData(id = "node_host", bounds = bounds, children = listOf(webArea, nativeNode))
            val result =
                MultiWindowResult(windows = listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = host)))
            val matcher = NodeActionMatcher(gateFor(result), WebViewNodeMerger())
            val shown = matcher.shownLabels(result, nativeNode)

            assertEquals(0, matcher.indexOf(result, nativeNode, shown, "Call [MERGED]"))
        }

    @Test
    fun `on a screen without web content a label matches only as read, with one redaction`() =
        runTest {
            val host = AccessibilityNodeData(id = "node_host", bounds = bounds, children = listOf(nativeNode))
            val result =
                MultiWindowResult(windows = listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = host)))
            val gate = gateFor(result)
            val matcher = NodeActionMatcher(gate, WebViewNodeMerger())
            val shown = matcher.shownLabels(result, nativeNode)

            assertEquals(-1, matcher.indexOf(result, nativeNode, shown, "Call [MERGED]"))
            coVerify(exactly = 1) { gate.nodeTexts(any(), any()) }
        }

    @Test
    fun `a node without actions matches nothing and is never redacted in a merged tree`() =
        runTest {
            val bare = webNode.copy(actions = emptyList())
            val result = resultOf(bare)
            val gate = gateFor(result)
            val matcher = NodeActionMatcher(gate, WebViewNodeMerger())
            val shown = matcher.shownLabels(result, bare)

            assertEquals(-1, matcher.indexOf(result, bare, shown, "Call [MERGED]"))
            coVerify(exactly = 1) { gate.nodeTexts(any(), any()) }
        }
}
