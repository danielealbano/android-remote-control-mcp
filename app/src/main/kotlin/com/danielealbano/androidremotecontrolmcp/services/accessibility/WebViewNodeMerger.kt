package com.danielealbano.androidremotecontrolmcp.services.accessibility

import javax.inject.Inject

/**
 * Collapses Chromium WebView accessibility subtrees into a far smaller tree WITHOUT losing any
 * text content or any interactive (tap) target.
 *
 * WebViews expose one accessibility node per DOM element, which explodes the node count on
 * content-heavy pages (a single article list can be thousands of nodes). Chrome and the Android
 * System WebView populate [AccessibilityNodeData.webRole] (the Chromium DOM role) on every web
 * node, which lets us scope this collapsing strictly to web content — native and Compose subtrees
 * (where `webRole` is null) are returned untouched, by reference.
 *
 * ## Algorithm (single bottom-up pass = fixpoint)
 *
 * Within a web subtree, each node is classified and processed after its children:
 * - **Anchor** — an interactive node (clickable / long-clickable / editable / scrollable / with
 *   labelled actions). Always kept (it is a tap target, or its actions are performed on it). Its
 *   text becomes its accessible name plus any descendant content not already present in that
 *   name. Keeps its original id and bounds, so element actions still resolve it.
 * - **Block** — a semantic block role ([BLOCK_ROLES]: article, listItem, section, figure, …).
 *   Kept when it carries any content, holding the merged markdown of its non-block descendants.
 *   Empty blocks are dropped and their kept descendants bubble up.
 * - **Plain** — everything else (staticText, heading, image, paragraph, genericContainer, …). The
 *   node itself is removed; its content bubbles up (as markdown) into the nearest kept ancestor and
 *   its kept descendants are re-parented to that ancestor.
 *
 * ## Markdown annotation
 *
 * Non-interactive content is annotated with minimal markdown so structure survives the collapse:
 * `# ` for headings, `- ` for list items, `![alt](url)` for images, `[text](url)` for links. This
 * is intentionally NOT a full HTML→Markdown conversion.
 *
 * ## Invariants (verified by tests)
 * - **No text is ever dropped**: every text/contentDescription token (and every image URL) present
 *   in the input survives somewhere in the output.
 * - **No two interactive nodes are merged together**: every tap target keeps its own node.
 * - Native / Compose subtrees are returned unchanged (same instances).
 *
 * This runs only on the `get_screen_state` output. The node cache used by element/action tools is
 * populated separately from the original (un-merged) tree, and merged anchors keep their original
 * ids, so taps and look-ups are unaffected.
 */
class WebViewNodeMerger
    @Inject
    constructor() {
        /**
         * Returns a copy of [result] with every Chromium WebView subtree collapsed. Windows and
         * native subtrees with no web nodes are returned by reference (no allocation).
         */
        fun merge(result: MultiWindowResult): MultiWindowResult {
            val windows = result.windows
            var changed = false
            val newWindows =
                windows.map { window ->
                    val newTree = transform(window.tree)
                    if (newTree !== window.tree) {
                        changed = true
                        window.copy(tree = newTree)
                    } else {
                        window
                    }
                }
            return if (changed) result.copy(windows = newWindows) else result
        }

        /**
         * Walks the native tree until a web-area entry node ([AccessibilityNodeData.webRole] set on a
         * node whose ancestors are native) is reached, then collapses that subtree via [mergeWebArea].
         * Native nodes are rebuilt only along paths that actually contain a web area; subtrees with no
         * web content are returned by reference.
         */
        private fun transform(node: AccessibilityNodeData): AccessibilityNodeData {
            if (node.webRole != null) {
                return mergeWebArea(node)
            }
            // Rebuild native nodes only when a descendant web area was actually collapsed; otherwise
            // return the same instance (an empty child list maps to no change automatically).
            var changed = false
            val newChildren =
                node.children.map { child ->
                    val newChild = transform(child)
                    if (newChild !== child) changed = true
                    newChild
                }
            return if (changed) node.copy(children = newChildren) else node
        }

        /**
         * Collapses the web subtree rooted at [webRoot]. The root is retained as the container for the
         * web area (preserving its position, id and bounds in the overall tree); its kept descendants
         * become its children and any leftover bubbled-up content is folded into its text so nothing
         * is lost.
         */
        private fun mergeWebArea(webRoot: AccessibilityNodeData): AccessibilityNodeData {
            val keptChildren = ArrayList<AccessibilityNodeData>()
            val looseParts = ArrayList<String>()
            looseParts.add(ownContent(webRoot))
            for (child in webRoot.children) {
                val out = walk(child)
                keptChildren.addAll(out.kept)
                if (out.looseMarkdown.isNotEmpty()) looseParts.add(out.looseMarkdown)
            }
            val containerText = dedupJoin(looseParts)
            return webRoot.copy(
                text = containerText.ifEmpty { null },
                contentDescription = null,
                children = keptChildren,
            )
        }

        /**
         * Result of collapsing one subtree: the kept nodes to attach to the nearest kept ancestor
         * (each a fully-collapsed subtree), plus any markdown content that must bubble further up.
         */
        private data class WalkOut(
            val kept: List<AccessibilityNodeData>,
            val looseMarkdown: String,
        )

        private fun walk(node: AccessibilityNodeData): WalkOut {
            val childKept = ArrayList<AccessibilityNodeData>()
            val childMarkdown = ArrayList<String>()
            for (child in node.children) {
                val out = walk(child)
                childKept.addAll(out.kept)
                if (out.looseMarkdown.isNotEmpty()) childMarkdown.add(out.looseMarkdown)
            }

            return when {
                isInteractive(node) -> {
                    // Use the role-aware ownContent so an image/link tap target keeps its targetUrl
                    // (the TSV output carries no URL column, so a plain name would lose it entirely);
                    // fall back to the plain name for other interactive roles.
                    val name = ownContent(node).ifEmpty { rawContent(node) }
                    val text = dedupJoin(listOf(name) + childMarkdown)
                    val anchor =
                        node.copy(
                            text = text.ifEmpty { null },
                            contentDescription = null,
                            children = childKept,
                        )
                    WalkOut(listOf(anchor), "")
                }

                node.webRole in BLOCK_ROLES -> {
                    val text = dedupJoin(listOf(ownContent(node)) + childMarkdown)
                    if (text.isEmpty()) {
                        // Empty structural block: drop it, pass its kept descendants upward.
                        WalkOut(childKept, "")
                    } else {
                        val block =
                            node.copy(
                                text = text,
                                contentDescription = null,
                                children = childKept,
                            )
                        WalkOut(listOf(block), "")
                    }
                }

                else -> {
                    // Plain node: remove it; bubble its content up and re-parent its kept descendants.
                    WalkOut(childKept, dedupJoin(listOf(ownContent(node)) + childMarkdown))
                }
            }
        }

        /**
         * Whether a node must be preserved as its own node: a tap target, or a node whose labelled
         * actions are performed on it.
         */
        private fun isInteractive(node: AccessibilityNodeData): Boolean =
            node.clickable || node.longClickable || node.editable || node.scrollable || node.actions.isNotEmpty()

        /**
         * The node's own text/contentDescription, combined so neither is ever dropped (with redundant
         * token-subset duplicates removed). No markdown decoration — used for interactive accessible
         * names and as the raw basis for [ownContent].
         */
        private fun rawContent(node: AccessibilityNodeData): String =
            dedupJoin(listOf(node.text.orEmpty().trim(), node.contentDescription.orEmpty().trim()))

        /**
         * The node's own content as minimal markdown, based on its [AccessibilityNodeData.webRole].
         * Always preserves every text/contentDescription token; adds the URL for images and links.
         */
        private fun ownContent(node: AccessibilityNodeData): String {
            val base = rawContent(node)
            val url = node.targetUrl.orEmpty().trim()
            return when (node.webRole) {
                ROLE_IMAGE -> if (base.isNotEmpty() || url.isNotEmpty()) "![$base]($url)" else ""
                ROLE_HEADING -> if (base.isNotEmpty()) "# $base" else ""
                ROLE_LIST_ITEM -> if (base.isNotEmpty()) "- $base" else ""
                ROLE_LINK -> if (url.isNotEmpty()) "[$base]($url)" else base
                else -> base
            }
        }

        /**
         * Joins non-empty [parts] with spaces, skipping any part whose tokens are already entirely
         * present in earlier parts (Chromium frequently duplicates child text onto a parent's
         * accessible name). A part is dropped ONLY when every one of its tokens — including
         * single-character tokens such as digits — is already present, so it can never discard new
         * content (the worst case is keeping a near-duplicate, never losing text).
         */
        private fun dedupJoin(parts: List<String>): String {
            val seen = HashSet<String>()
            val out = ArrayList<String>()
            for (part in parts) {
                if (part.isEmpty()) continue
                val partTokens = tokens(part)
                val isNewContent = partTokens.isEmpty() || !seen.containsAll(partTokens)
                if (isNewContent) {
                    seen.addAll(partTokens)
                    out.add(part)
                }
            }
            return out.joinToString(" ").trim()
        }

        /**
         * Lowercased content tokens used for redundancy detection. A token is either a word/url run
         * (`[\w/.:-]+`, so URLs and markdown stay intact while `![ ]( )#` act as separators) OR any
         * other single non-whitespace glyph (`+`, `%`, `$`, `★`, emoji, …). Including those glyphs —
         * and single-character word tokens like a lone digit — ensures [dedupJoin] can NEVER hide
         * visible content from its subset check and silently drop it.
         */
        private fun tokens(text: String): Set<String> =
            TOKEN_REGEX
                .findAll(text.lowercase())
                .map { it.value }
                .toSet()

        companion object {
            private const val ROLE_IMAGE = "image"
            private const val ROLE_HEADING = "heading"
            private const val ROLE_LIST_ITEM = "listItem"
            private const val ROLE_LINK = "link"

            /** Chromium DOM roles treated as content blocks: kept (when non-empty) to preserve structure. */
            private val BLOCK_ROLES =
                setOf(
                    "article",
                    "listItem",
                    "list",
                    "region",
                    "section",
                    "sectionWithoutName",
                    "navigation",
                    "header",
                    "footer",
                    "main",
                    "complementary",
                    "banner",
                    "figure",
                    "figcaption",
                )

            private val TOKEN_REGEX = Regex("[\\w/.:-]+|\\S")
        }
    }
