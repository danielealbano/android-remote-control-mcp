package com.danielealbano.androidremotecontrolmcp.services.accessibility

import javax.inject.Inject

/**
 * Formats an [AccessibilityNodeData] tree into a compact flat TSV representation
 * optimized for LLM token efficiency.
 *
 * Output format:
 * - Line 1: `note:structural-only nodes are omitted from the tree`
 * - Line 2: `note:certain elements are custom and will not be properly reported, ...`
 * - Line 3: `note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable
 *   foc=focusable scr=scrollable edt=editable ena=enabled`
 * - Line 4: `note:offscreen items require scroll_to_node before interaction`
 * - Line 5: `app:<package> activity:<activity>`
 * - Line 6: `screen:<w>x<h> density:<dpi> orientation:<orientation>`
 * - Line 7: TSV header: `node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
 * - Lines 8+: one TSV row per kept node (flat, no depth)
 * - Line N: `hierarchy:`
 * - Lines N+1+: one line per kept node, indented by 2 spaces per nesting depth
 *
 * Nodes are filtered: a node is KEPT if ANY of:
 * - has non-null, non-empty text
 * - has non-null, non-empty contentDescription
 * - has non-null, non-empty resourceId
 * - is clickable
 * - is longClickable
 * - is scrollable
 * - is editable
 *
 * Filtered nodes are skipped in output, but their children are still
 * walked and may appear if they independently pass the filter.
 */
class CompactTreeFormatter
    @Inject
    constructor() {
        /**
         * Formats the full screen state as a compact string.
         */
        fun format(
            tree: AccessibilityNodeData,
            packageName: String,
            activityName: String,
            screenInfo: ScreenInfo,
        ): String {
            val sb = StringBuilder()

            // Line 1: note
            sb.appendLine(NOTE_LINE)

            // Line 2: note about custom elements
            sb.appendLine(NOTE_LINE_CUSTOM_ELEMENTS)

            // Line 3: flags legend
            sb.appendLine(NOTE_LINE_FLAGS_LEGEND)

            // Line 4: offscreen hint
            sb.appendLine(NOTE_LINE_OFFSCREEN_HINT)

            // Line 5: app metadata
            sb.appendLine("app:$packageName activity:$activityName")

            // Line 6: screen info
            sb.appendLine(
                "screen:${screenInfo.width}x${screenInfo.height} " +
                    "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
            )

            // Line 7: header
            sb.appendLine(HEADER)

            // Lines 8+: walk tree and append kept nodes
            val hierarchySb = StringBuilder()
            walkTree(
                tree,
                visitors =
                    listOf(
                        { node, _ -> appendElementRow(sb, node) },
                        { node, depth ->
                            repeat(depth) { hierarchySb.append(HIERARCHY_INDENT) }
                            hierarchySb.appendLine(node.id)
                        },
                    ),
            )
            sb.appendLine(HIERARCHY_HEADER)
            sb.append(hierarchySb)

            return sb.toString().trimEnd('\n')
        }

        private fun walkTree(
            node: AccessibilityNodeData,
            depth: Int = 0,
            visitors: List<(node: AccessibilityNodeData, depth: Int) -> Unit>,
            options: ScreenStateFormatOptions = ScreenStateFormatOptions(),
        ) {
            val isListed = isListed(node, options)
            if (isListed) {
                for (visitor in visitors) {
                    visitor(node, depth)
                }
            }
            val childDepth = if (isListed) depth + 1 else depth
            for (child in node.children) {
                walkTree(child, childDepth, visitors, options)
            }
        }

        internal fun appendElementRow(
            sb: StringBuilder,
            node: AccessibilityNodeData,
        ) {
            val id = node.id
            val className = simplifyClassName(node.className)
            // Merged WebView nodes carry content collapsed from many DOM nodes that exists ONLY in
            // this output (get_node_details reads the original, un-merged node cache). Truncating it
            // would lose text, so web nodes are never truncated.
            val truncate = node.webRole == null
            val text = sanitizeText(node.text, truncate)
            val desc = sanitizeText(node.contentDescription, truncate)
            val resId = sanitizeResourceId(node.resourceId)
            val bounds =
                "${node.bounds.left},${node.bounds.top}," +
                    "${node.bounds.right},${node.bounds.bottom}"
            val flags = buildFlags(node)
            sb.appendLine(
                "$id$SEP$className$SEP$text$SEP$desc$SEP$resId$SEP$bounds$SEP$flags",
            )
        }

        /**
         * Formats multi-window screen state as a compact string.
         *
         * Each window gets its own section with a header line containing window metadata,
         * followed by a TSV column header and element rows, then its `hierarchy:` block unless
         * [options] leaves it out. The default [options] give the full output.
         */
        fun formatMultiWindow(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            options: ScreenStateFormatOptions = ScreenStateFormatOptions(),
        ): String {
            val sb = StringBuilder()

            // Degradation note (if applicable)
            if (result.degraded) {
                sb.appendLine(DEGRADATION_NOTE)
            }

            // Note lines
            sb.appendLine(NOTE_LINE)
            sb.appendLine(NOTE_LINE_CUSTOM_ELEMENTS)
            sb.appendLine(NOTE_LINE_FLAGS_LEGEND)
            sb.appendLine(offscreenNote(options))

            // Screen info (global)
            sb.appendLine(
                "screen:${screenInfo.width}x${screenInfo.height} " +
                    "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
            )

            // Per-window sections
            for (windowData in result.windows) {
                sb.appendLine(buildWindowHeader(windowData))
                sb.appendLine(HEADER)
                val hierarchySb = StringBuilder()
                val rowVisitor: (AccessibilityNodeData, Int) -> Unit = { node, _ -> appendElementRow(sb, node) }
                val hierarchyVisitor: (AccessibilityNodeData, Int) -> Unit = { node, depth ->
                    repeat(depth) { hierarchySb.append(HIERARCHY_INDENT) }
                    hierarchySb.appendLine(node.id)
                }
                val visitors =
                    if (options.includeHierarchy) listOf(rowVisitor, hierarchyVisitor) else listOf(rowVisitor)
                walkTree(windowData.tree, visitors = visitors, options = options)
                if (options.includeHierarchy) {
                    sb.appendLine(HIERARCHY_HEADER)
                    sb.append(hierarchySb)
                }
            }

            return sb.toString().trimEnd('\n')
        }

        /**
         * Builds the window header line for a single window.
         *
         * Format: `--- window:N type:TYPE pkg:PKG title:TITLE [activity:ACT] layer:N focused:BOOL ---`
         *
         * The `activity:` field is omitted when [WindowData.activityName] is null.
         */
        internal fun buildWindowHeader(windowData: WindowData): String =
            buildString {
                append("--- ")
                append("window:${windowData.windowId} ")
                append("type:${windowData.windowType} ")
                append("pkg:${windowData.packageName ?: "unknown"} ")
                append("title:${windowData.title ?: "unknown"} ")
                if (windowData.activityName != null) {
                    append("activity:${windowData.activityName} ")
                }
                append("layer:${windowData.layer} ")
                append("focused:${windowData.focused}")
                append(" ---")
            }

        /**
         * Determines whether a node should be included in the compact output.
         */
        internal fun shouldKeepNode(node: AccessibilityNodeData): Boolean =
            !node.text.isNullOrEmpty() ||
                !node.contentDescription.isNullOrEmpty() ||
                !node.resourceId.isNullOrEmpty() ||
                node.clickable ||
                node.longClickable ||
                node.scrollable ||
                node.editable

        /**
         * Strips a fully-qualified class name to its simple name.
         * e.g., "android.widget.Button" → "Button"
         * Returns "-" if null or empty.
         */
        internal fun simplifyClassName(className: String?): String {
            if (className.isNullOrEmpty()) return NULL_VALUE
            val lastDot = className.lastIndexOf('.')
            return if (lastDot >= 0) className.substring(lastDot + 1) else className
        }

        /**
         * Sanitizes and truncates text for TSV output.
         * 1. Replaces tabs, newlines, carriage returns with spaces.
         * 2. Trims whitespace.
         * 3. Returns "-" if null, empty, or whitespace-only after sanitization.
         * 4. Truncates to [MAX_TEXT_LENGTH] characters with [TRUNCATION_SUFFIX] if exceeded,
         *    UNLESS [truncate] is false (used for merged WebView nodes whose collapsed content
         *    exists only in this output and must not be lost).
         */
        internal fun sanitizeText(
            text: String?,
            truncate: Boolean = true,
        ): String {
            val sanitized =
                text
                    ?.replace('\t', ' ')
                    ?.replace('\n', ' ')
                    ?.replace('\r', ' ')
                    ?.trim()
                    ?.ifEmpty { null }
            return when {
                sanitized == null -> {
                    NULL_VALUE
                }

                truncate && sanitized.length > MAX_TEXT_LENGTH -> {
                    sanitized.substring(0, MAX_TEXT_LENGTH) + TRUNCATION_SUFFIX
                }

                else -> {
                    sanitized
                }
            }
        }

        /**
         * Sanitizes resourceId for TSV output.
         * Returns "-" if null or empty. Does NOT truncate (resourceIds are short).
         * Replaces tabs/newlines with spaces (defensive).
         */
        internal fun sanitizeResourceId(resourceId: String?): String {
            if (resourceId.isNullOrEmpty()) return NULL_VALUE
            val sanitized =
                resourceId
                    .replace('\t', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
            return if (sanitized.isEmpty()) NULL_VALUE else sanitized
        }

        /**
         * Builds the comma-separated flags string for a node.
         * The first flag is always `on` (onscreen) or `off` (offscreen).
         * Subsequent flags are appended only when `true`.
         * Order: on/off, clk, lclk, foc, scr, edt, ena
         */
        internal fun buildFlags(node: AccessibilityNodeData): String =
            buildString {
                append(if (node.visible) FLAG_ONSCREEN else FLAG_OFFSCREEN)
                if (node.clickable) {
                    append(FLAG_SEPARATOR)
                    append(FLAG_CLICKABLE)
                }
                if (node.longClickable) {
                    append(FLAG_SEPARATOR)
                    append(FLAG_LONG_CLICKABLE)
                }
                if (node.focusable) {
                    append(FLAG_SEPARATOR)
                    append(FLAG_FOCUSABLE)
                }
                if (node.scrollable) {
                    append(FLAG_SEPARATOR)
                    append(FLAG_SCROLLABLE)
                }
                if (node.editable) {
                    append(FLAG_SEPARATOR)
                    append(FLAG_EDITABLE)
                }
                if (node.enabled) {
                    append(FLAG_SEPARATOR)
                    append(FLAG_ENABLED)
                }
            }

        companion object {
            const val PAGE_SIZE = 200
            private const val SEP = "\t"
            const val COLUMN_SEPARATOR = "\t"
            const val DEGRADATION_NOTE =
                "note:DEGRADED — multi-window unavailable, only active window reported"
            const val NULL_VALUE = "-"
            const val MAX_TEXT_LENGTH = 100
            const val TRUNCATION_SUFFIX = "...truncated"
            const val NOTE_LINE = "note:structural-only nodes are omitted from the tree"
            const val NOTE_LINE_CUSTOM_ELEMENTS =
                "note:certain elements are custom and will not be properly reported, " +
                    "if needed or if tools are not working as expected set " +
                    "include_screenshot=true to see the screen and take what you see into account"
            const val NOTE_LINE_FLAGS_LEGEND =
                "note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable " +
                    "foc=focusable scr=scrollable edt=editable ena=enabled"
            const val NOTE_LINE_OFFSCREEN_HINT =
                "note:offscreen items require scroll_to_node before interaction"
            const val NOTE_LINE_OFFSCREEN_OMITTED =
                "note:offscreen nodes are not listed (include_offscreen=false); " +
                    "call without it, or scroll, to see them"
            const val FLAG_ONSCREEN = "on"
            const val FLAG_OFFSCREEN = "off"
            const val FLAG_CLICKABLE = "clk"
            const val FLAG_LONG_CLICKABLE = "lclk"
            const val FLAG_FOCUSABLE = "foc"
            const val FLAG_SCROLLABLE = "scr"
            const val FLAG_EDITABLE = "edt"
            const val FLAG_ENABLED = "ena"
            const val HIERARCHY_HEADER = "hierarchy:"
            internal const val HIERARCHY_INDENT = "  "
            private const val FLAG_SEPARATOR = ","
            const val HEADER =
                "node_id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}text${COLUMN_SEPARATOR}" +
                    "desc${COLUMN_SEPARATOR}res_id${COLUMN_SEPARATOR}bounds${COLUMN_SEPARATOR}flags"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Pagination (Plan 47)
//
// Implemented as top-level/extension functions to keep CompactTreeFormatter within detekt's
// per-class function-count threshold while reusing its internal rendering helpers
// (shouldKeepNode, buildWindowHeader, appendElementRow) and companion constants.
// ─────────────────────────────────────────────────────────────────────────────

/** True when [node] gets a TSV row (and a hierarchy line) under [options]. */
internal fun CompactTreeFormatter.isListed(
    node: AccessibilityNodeData,
    options: ScreenStateFormatOptions,
): Boolean = shouldKeepNode(node) && (options.includeOffscreen || node.visible)

/** The offscreen note line matching [options]. */
private fun offscreenNote(options: ScreenStateFormatOptions): String =
    if (options.includeOffscreen) {
        CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT
    } else {
        CompactTreeFormatter.NOTE_LINE_OFFSCREEN_OMITTED
    }

/** Total number of listed nodes across all windows under [options] (equals the number of TSV rows emitted). */
internal fun CompactTreeFormatter.countKeptNodes(
    result: MultiWindowResult,
    options: ScreenStateFormatOptions = ScreenStateFormatOptions(),
): Int {
    fun count(node: AccessibilityNodeData): Int {
        var c = if (isListed(node, options)) 1 else 0
        for (child in node.children) c += count(child)
        return c
    }
    return result.windows.sumOf { count(it.tree) }
}

/**
 * Formats a single page (1-based [page]) of [snapshot] as a compact string.
 *
 * The header/notes/window-header/TSV-row/hierarchy formats are IDENTICAL to
 * [CompactTreeFormatter.formatMultiWindow]; the only additions are the `page:` line and the
 * trailing cursor note. A window section is emitted only when it contributes at least one row to
 * this page. Each window's `hierarchy:` block lists the page's rows for that window PLUS their
 * listed ancestors (listed-ancestor closure), preserving the listed-depth indentation. The snapshot's
 * [ScreenStateSnapshot.options] apply, so every page of a capture is rendered the same way.
 *
 * [page] MUST be within 1..[ScreenStateSnapshot.totalPages] (validated by the caller).
 */
internal fun CompactTreeFormatter.formatMultiWindowPage(
    snapshot: ScreenStateSnapshot,
    page: Int,
): String {
    val result = snapshot.result
    val screenInfo = snapshot.screenInfo
    val startIndex = (page - 1) * CompactTreeFormatter.PAGE_SIZE
    val endIndexExclusive = minOf(page * CompactTreeFormatter.PAGE_SIZE, snapshot.totalKeptNodes)
    val options = snapshot.options
    val perWindowKept = result.windows.map { collectKeptNodes(this, it.tree, options) }

    val sb = StringBuilder()
    if (result.degraded) sb.appendLine(CompactTreeFormatter.DEGRADATION_NOTE)
    sb.appendLine(CompactTreeFormatter.NOTE_LINE)
    sb.appendLine(CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS)
    sb.appendLine(CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND)
    sb.appendLine(offscreenNote(options))
    sb.appendLine(
        "screen:${screenInfo.width}x${screenInfo.height} " +
            "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
    )
    sb.appendLine(
        "page:$page/${snapshot.totalPages} snapshot:${snapshot.id} " +
            "nodes:${startIndex + 1}-$endIndexExclusive/${snapshot.totalKeptNodes}",
    )

    var windowGlobalStart = 0
    for ((wIdx, windowData) in result.windows.withIndex()) {
        val kept = perWindowKept[wIdx]
        val windowEnd = windowGlobalStart + kept.size
        val overlapStart = maxOf(startIndex, windowGlobalStart)
        val overlapEnd = minOf(endIndexExclusive, windowEnd)
        if (overlapStart < overlapEnd) {
            val pageNodes = kept.subList(overlapStart - windowGlobalStart, overlapEnd - windowGlobalStart)
            appendWindowPageSection(this, sb, windowData, pageNodes, options)
        }
        windowGlobalStart = windowEnd
    }

    sb.appendLine(buildPaginationNote(snapshot, page))
    return sb.toString().trimEnd('\n')
}

private fun buildPaginationNote(
    snapshot: ScreenStateSnapshot,
    page: Int,
): String =
    if (page < snapshot.totalPages) {
        "note:more nodes available — call get_screen_state with cursor " +
            "\"${snapshot.id}.${page + 1}\" to continue. You do NOT need to fetch every page; " +
            "stop once you have found what you need. This cursor is tied to this screen " +
            "snapshot; if the screen changed, call without a cursor for a fresh one."
    } else {
        "note:end of snapshot (page $page/${snapshot.totalPages}). You do NOT need to have " +
            "fetched every page; stop once you have found what you need."
    }

/** Pre-order list of listed nodes (same order/filter as walkTree's TSV emission). */
private fun collectKeptNodes(
    formatter: CompactTreeFormatter,
    root: AccessibilityNodeData,
    options: ScreenStateFormatOptions,
): List<AccessibilityNodeData> {
    val out = ArrayList<AccessibilityNodeData>()

    fun walk(node: AccessibilityNodeData) {
        if (formatter.isListed(node, options)) out.add(node)
        for (child in node.children) walk(child)
    }
    walk(root)
    return out
}

/**
 * Emits a window section for [pageNodes]: window header, TSV rows, and (unless [options] leaves it
 * out) a `hierarchy:` block holding those rows PLUS their listed ancestors (listed-ancestor closure),
 * at the same listed-depth indentation as [CompactTreeFormatter.formatMultiWindow].
 */
private fun appendWindowPageSection(
    formatter: CompactTreeFormatter,
    sb: StringBuilder,
    windowData: WindowData,
    pageNodes: List<AccessibilityNodeData>,
    options: ScreenStateFormatOptions,
) {
    sb.appendLine(formatter.buildWindowHeader(windowData))
    sb.appendLine(CompactTreeFormatter.HEADER)
    for (node in pageNodes) formatter.appendElementRow(sb, node)
    if (options.includeHierarchy) appendPageHierarchy(formatter, sb, windowData, pageNodes, options)
}

/** The `hierarchy:` block of a page: [pageNodes] plus their listed ancestors, by listed depth. */
private fun appendPageHierarchy(
    formatter: CompactTreeFormatter,
    sb: StringBuilder,
    windowData: WindowData,
    pageNodes: List<AccessibilityNodeData>,
    options: ScreenStateFormatOptions,
) {
    val pageNodeIds = pageNodes.mapTo(HashSet()) { it.id }
    val closure = HashSet<String>()

    fun covers(node: AccessibilityNodeData): Boolean {
        var subtreeCovers = node.id in pageNodeIds
        for (child in node.children) {
            if (covers(child)) subtreeCovers = true
        }
        if (subtreeCovers && formatter.isListed(node, options)) closure.add(node.id)
        return subtreeCovers
    }
    covers(windowData.tree)

    sb.appendLine(CompactTreeFormatter.HIERARCHY_HEADER)

    fun appendHierarchy(
        node: AccessibilityNodeData,
        depth: Int,
    ) {
        val isListed = formatter.isListed(node, options)
        if (isListed && node.id in closure) {
            repeat(depth) { sb.append(CompactTreeFormatter.HIERARCHY_INDENT) }
            sb.appendLine(node.id)
        }
        val childDepth = if (isListed) depth + 1 else depth
        for (child in node.children) appendHierarchy(child, childDepth)
    }
    appendHierarchy(windowData.tree, 0)
}
