package com.danielealbano.androidremotecontrolmcp.services.accessibility

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WebViewNodeMerger")
class WebViewNodeMergerTest {
    private val merger = WebViewNodeMerger()
    private val json = Json { ignoreUnknownKeys = true }

    // ── helpers ──────────────────────────────────────────────────────────────

    // Mirrors the production tokenizer (word/url runs OR any single non-whitespace glyph) so the
    // content-preservation assertions cannot miss a dropped digit, lone letter or glyph (★/+/%/$).
    private val tokenRegex = Regex("[\\w/.:-]+|\\S")

    private fun tokens(text: String?): Set<String> =
        tokenRegex
            .findAll(text.orEmpty().lowercase())
            .map { it.value }
            .toSet()

    private val baseNode =
        AccessibilityNodeData(id = "n", bounds = BoundsData(0, 0, 1, 1), enabled = true, visible = true)

    private fun node(
        id: String,
        webRole: String? = null,
        text: String? = null,
        desc: String? = null,
        children: List<AccessibilityNodeData> = emptyList(),
    ): AccessibilityNodeData =
        baseNode.copy(
            id = id,
            text = text,
            contentDescription = desc,
            webRole = webRole,
            children = children,
        )

    /** Marks a node as a clickable tap target. */
    private fun clickable(node: AccessibilityNodeData): AccessibilityNodeData = node.copy(clickable = true)

    private fun result(tree: AccessibilityNodeData): MultiWindowResult =
        MultiWindowResult(
            windows = listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = tree)),
        )

    private fun mergedTree(tree: AccessibilityNodeData): AccessibilityNodeData {
        val merged = merger.merge(result(tree))
        return merged.windows[0].tree
    }

    private fun flatten(node: AccessibilityNodeData): List<AccessibilityNodeData> {
        val descendants = node.children.flatMap { flatten(it) }
        return listOf(node) + descendants
    }

    /** Content that MUST survive: every text/contentDescription token + every image/link target URL. */
    private fun requiredTokens(node: AccessibilityNodeData): Set<String> {
        val out = HashSet<String>()
        for (n in flatten(node)) {
            out.addAll(tokens(n.text))
            out.addAll(tokens(n.contentDescription))
            if (n.webRole == "image" || n.webRole == "link") out.addAll(tokens(n.targetUrl))
        }
        return out
    }

    /**
     * Content the TSV formatter actually EMITS: the `text` and `desc` columns only. `targetUrl` is
     * NOT a TSV column, so reading it here would mask a URL that the merge failed to fold into `text`.
     */
    private fun emittedTokens(node: AccessibilityNodeData): Set<String> {
        val out = HashSet<String>()
        for (n in flatten(node)) {
            out.addAll(tokens(n.text))
            out.addAll(tokens(n.contentDescription))
        }
        return out
    }

    private fun keptCount(node: AccessibilityNodeData): Int {
        val formatter = CompactTreeFormatter()
        return flatten(node).count { formatter.shouldKeepNode(it) }
    }

    private fun loadFixture(): AccessibilityNodeData {
        val text =
            this::class.java.getResource("/webview/webview_tree_fixture.json")!!.readText()
        return json.decodeFromString(AccessibilityNodeData.serializer(), text)
    }

    // ── fixture-driven invariants ────────────────────────────────────────────

    @Nested
    @DisplayName("realistic fixture")
    inner class FixtureTests {
        private val input = loadFixture()
        private val output = mergedTree(input)

        @Test
        @DisplayName("emits every input text/desc/url token in the formatter-visible output")
        fun losesNoContent() {
            // before = what MUST survive (incl. URLs); after = what the TSV columns actually emit.
            val before = requiredTokens(input)
            val after = emittedTokens(output)
            val lost = before - after
            assertTrue(lost.isEmpty(), "content tokens dropped by merge: $lost")
        }

        @Test
        @DisplayName("reduces node count by at least 25%")
        fun reducesNodeCount() {
            val before = keptCount(input)
            val after = keptCount(output)
            assertTrue(after < before, "expected fewer kept nodes ($after) than baseline ($before)")
            val reduction = (before - after).toDouble() / before
            assertTrue(reduction >= 0.25, "reduction $reduction below 25% (before=$before after=$after)")
        }

        @Test
        @DisplayName("preserves every interactive node with its id")
        fun preservesInteractiveNodes() {
            val interactiveIn =
                flatten(input)
                    .filter { it.clickable || it.longClickable || it.editable || it.scrollable }
                    .map { it.id }
                    .toSet()
            val idsOut = flatten(output).map { it.id }.toSet()
            assertTrue(
                idsOut.containsAll(interactiveIn),
                "missing interactive node ids: ${interactiveIn - idsOut}",
            )
        }

        @Test
        @DisplayName("annotates headings, list items and images with markdown")
        fun emitsMarkdown() {
            val allText = flatten(output).joinToString("\n") { it.text.orEmpty() }
            assertTrue(allText.contains("# Global Markets Rally"), "missing heading markdown")
            assertTrue(allText.contains("- Team Alpha wins the final"), "missing list-item markdown")
            assertTrue(allText.contains("![Markets chart]"), "missing image markdown")
        }

        @Test
        @DisplayName("leaves native subtree nodes untouched by reference")
        fun nativePassthrough() {
            val nativeIn = flatten(input).first { it.id == "native_toolbar" }
            val nativeOut = flatten(output).first { it.id == "native_toolbar" }
            assertSame(nativeIn, nativeOut, "native node should not be copied")
        }
    }

    // ── per-branch behaviour ─────────────────────────────────────────────────

    @Test
    @DisplayName("returns the same result instance when there are no web nodes")
    fun noWebNodesReturnsSameInstance() {
        val native =
            node(
                "root",
                children =
                    listOf(
                        node("a", text = "Hello"),
                        clickable(node("b", text = "World")),
                    ),
            )
        val input = result(native)
        assertSame(input, merger.merge(input))
    }

    @Test
    @DisplayName("absorbs redundant child text into a clickable without duplicating it")
    fun dedupAbsorbIntoAnchor() {
        val web =
            webArea(
                clickable(
                    node(
                        "link",
                        webRole = "link",
                        desc = "Buy now",
                        children = listOf(node("t", webRole = "staticText", text = "Buy now")),
                    ),
                ),
            )
        val anchor = mergedWeb(web).children.single()
        assertEquals("link", anchor.id)
        assertEquals("Buy now", anchor.text)
        assertTrue(anchor.children.isEmpty(), "redundant child should be absorbed, not kept")
    }

    @Test
    @DisplayName("absorbs non-redundant child content into a clickable")
    fun absorbExtraChildContent() {
        val web =
            webArea(
                clickable(
                    node(
                        "link",
                        webRole = "link",
                        desc = "Title",
                        children = listOf(node("t", webRole = "staticText", text = "Extra detail")),
                    ),
                ),
            )
        val text =
            mergedWeb(web)
                .children
                .single()
                .text
                .orEmpty()
        assertTrue(text.contains("Title"), "lost link name")
        assertTrue(text.contains("Extra detail"), "lost child content")
    }

    @Test
    @DisplayName("annotates a heading with leading '# '")
    fun headingMarkdown() {
        val web = webArea(node("h", webRole = "heading", text = "Hi there"))
        assertTrue(mergedWeb(web).text!!.contains("# Hi there"))
    }

    @Test
    @DisplayName("annotates an image with '![alt](url)'")
    fun imageMarkdown() {
        val image =
            baseNode.copy(
                id = "img",
                webRole = "image",
                contentDescription = "Cat",
                targetUrl = "https://e.example/c.png",
            )
        assertTrue(mergedWeb(webArea(image)).text!!.contains("![Cat](https://e.example/c.png)"))
    }

    @Test
    @DisplayName("annotates a list item with leading '- '")
    fun listItemMarkdown() {
        val web =
            webArea(
                node(
                    "list",
                    webRole = "list",
                    children = listOf(node("li", webRole = "listItem", text = "One")),
                ),
            )
        val merged = mergedWeb(web)
        val allText = (listOf(merged) + merged.children).joinToString("\n") { it.text.orEmpty() }
        assertTrue(allText.contains("- One"), "missing list-item markdown in: $allText")
    }

    @Test
    @DisplayName("annotates a non-interactive link with '[text](url)'")
    fun nonInteractiveLinkMarkdown() {
        val link =
            baseNode.copy(id = "l", webRole = "link", text = "Docs", targetUrl = "https://e.example/d")
        assertTrue(mergedWeb(webArea(link)).text!!.contains("[Docs](https://e.example/d)"))
    }

    @Test
    @DisplayName("never drops single-character content (e.g. a lone digit) during dedup")
    fun keepsSingleCharContent() {
        // Heading "Match result" + child "Match result 3": the digit "3" is a single-char token
        // whose siblings ("match", "result") are already seen — it must NOT be dropped.
        val web =
            webArea(
                node(
                    "article",
                    webRole = "article",
                    children =
                        listOf(
                            node("h", webRole = "heading", text = "Match result"),
                            node("s", webRole = "staticText", text = "Match result 3"),
                        ),
                ),
            )
        val allText = flatten(mergedWeb(web)).joinToString(" ") { it.text.orEmpty() }
        assertTrue(allText.contains("3"), "lone digit content must not be dropped: $allText")
    }

    @Test
    @DisplayName("preserves a link URL even when the link has no accessible name")
    fun nonInteractiveLinkUrlWithoutName() {
        val link = baseNode.copy(id = "l", webRole = "link", targetUrl = "https://e.example/x")
        assertTrue(mergedWeb(webArea(link)).text!!.contains("https://e.example/x"))
    }

    @Test
    @DisplayName("preserves the targetUrl of a CLICKABLE image (named tap target)")
    fun clickableImageKeepsUrl() {
        val image =
            baseNode.copy(
                id = "img",
                webRole = "image",
                contentDescription = "Chart",
                targetUrl = "https://e.example/chart.png",
                clickable = true,
            )
        val anchor = mergedWeb(webArea(image)).children.single()
        assertEquals("img", anchor.id)
        assertTrue(anchor.text!!.contains("https://e.example/chart.png"), "clickable image lost its url")
    }

    @Test
    @DisplayName("preserves the targetUrl of a CLICKABLE named link")
    fun clickableLinkKeepsUrl() {
        val link =
            baseNode.copy(
                id = "lnk",
                webRole = "link",
                contentDescription = "Open report",
                targetUrl = "https://e.example/report",
                clickable = true,
            )
        val anchor = mergedWeb(webArea(link)).children.single()
        val text = anchor.text.orEmpty()
        assertTrue(text.contains("Open report"), "clickable link lost its name")
        assertTrue(text.contains("https://e.example/report"), "clickable link lost its url")
    }

    @Test
    @DisplayName("never drops a non-word glyph (★) whose word tokens are already present")
    fun keepsGlyphContent() {
        // Parent "5 stars" + child "5★": the word token "5" is duplicated, but the ★ glyph is new
        // visible content and must survive.
        val web =
            webArea(
                node(
                    "article",
                    webRole = "article",
                    children =
                        listOf(
                            node("h", webRole = "heading", text = "5 stars"),
                            node("s", webRole = "staticText", text = "5★"),
                        ),
                ),
            )
        val allText = flatten(mergedWeb(web)).joinToString(" ") { it.text.orEmpty() }
        assertTrue(allText.contains("★"), "glyph content must not be dropped: $allText")
    }

    @Test
    @DisplayName("drops an empty structural block, keeping no node and no content")
    fun emptyBlockDropped() {
        val web =
            webArea(
                node(
                    "article",
                    webRole = "article",
                    children = listOf(node("g", webRole = "genericContainer")),
                ),
            )
        val merged = mergedWeb(web)
        assertTrue(merged.children.isEmpty(), "empty block should not be kept")
        assertNull(merged.text, "no content expected")
    }

    @Test
    @DisplayName("never merges two clickables together")
    fun twoClickablesKeptSeparate() {
        val web =
            webArea(
                node(
                    "article",
                    webRole = "article",
                    children =
                        listOf(
                            clickable(node("l1", webRole = "link", desc = "First")),
                            clickable(node("l2", webRole = "link", desc = "Second")),
                        ),
                ),
            )
        val ids = flatten(mergedWeb(web)).map { it.id }
        assertTrue(ids.contains("l1") && ids.contains("l2"), "both tap targets must survive: $ids")
    }

    @Test
    @DisplayName("keeps an anchor's original id and bounds")
    fun anchorKeepsIdAndBounds() {
        val link =
            AccessibilityNodeData(
                id = "link42",
                bounds = BoundsData(10, 20, 30, 40),
                webRole = "link",
                contentDescription = "Open",
                clickable = true,
                enabled = true,
                visible = true,
            )
        val anchor = mergedWeb(webArea(link)).children.single()
        assertEquals("link42", anchor.id)
        assertEquals(BoundsData(10, 20, 30, 40), anchor.bounds)
        assertTrue(anchor.clickable)
    }

    @Test
    @DisplayName("returns scrollable web nodes as preserved anchors")
    fun scrollableIsAnchor() {
        val scroller =
            baseNode.copy(
                id = "scroller",
                webRole = "genericContainer",
                scrollable = true,
                children = listOf(node("t", webRole = "staticText", text = "Inside")),
            )
        val ids = flatten(mergedWeb(webArea(scroller))).map { it.id }
        assertTrue(ids.contains("scroller"), "scrollable node must be preserved")
    }

    @Test
    @DisplayName("keeps a plain web node with labelled actions as an anchor, with its actions")
    fun plainNodeWithActionsIsKeptWithItsActions() {
        val voteBar =
            node("bar", webRole = "genericContainer", text = "349 votes")
                .copy(actions = listOf("Upvote", "Downvote"))
        val kept = flatten(mergedWeb(webArea(voteBar))).single { it.id == "bar" }
        assertEquals(listOf("Upvote", "Downvote"), kept.actions)
        assertEquals("349 votes", kept.text)
    }

    @Test
    @DisplayName("an interactive anchor keeps its actions")
    fun anchorKeepsItsActions() {
        val link = clickable(node("link", webRole = "link", desc = "Open")).copy(actions = listOf("Copy link"))
        val anchor = mergedWeb(webArea(link)).children.single()
        assertEquals(listOf("Copy link"), anchor.actions)
    }

    @Test
    @DisplayName("an empty block with labelled actions is kept, where one without would be dropped")
    fun emptyBlockWithActionsIsKept() {
        val article =
            node(
                "article",
                webRole = "article",
                children = listOf(node("g", webRole = "genericContainer")),
            ).copy(actions = listOf("Save"))
        val kept = flatten(mergedWeb(webArea(article))).single { it.id == "article" }
        assertEquals(listOf("Save"), kept.actions)
    }

    // ── small-tree helpers ───────────────────────────────────────────────────

    /** Wraps [children] under a rootWebArea web entry, itself under a native host. */
    private fun webArea(vararg children: AccessibilityNodeData): AccessibilityNodeData =
        node(
            "host",
            children =
                listOf(
                    node("webroot", webRole = "rootWebArea", children = children.toList()),
                ),
        )

    /** Merges [tree] and returns the collapsed rootWebArea node. */
    private fun mergedWeb(tree: AccessibilityNodeData): AccessibilityNodeData {
        val merged = mergedTree(tree)
        return flatten(merged).first { it.id == "webroot" }
    }
}
