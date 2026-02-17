package com.danielealbano.androidremotecontrolmcp.services.accessibility

import javax.inject.Inject

/**
 * Formats an [AccessibilityNodeData] tree into a compact flat TSV representation
 * optimized for LLM token efficiency.
 *
 * Output format:
 * - Line 1: `note:structural-only nodes are omitted from the tree`
 * - Line 2: `note:certain elements are custom and will not be properly reported, ...`
 * - Line 3: `note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled`
 * - Line 4: `note:offscreen items require scroll_to_element before interaction`
 * - Line 5: `app:<package> activity:<activity>`
 * - Line 6: `screen:<w>x<h> density:<dpi> orientation:<orientation>`
 * - Line 7: TSV header: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
 * - Lines 8+: one TSV row per kept node (flat, no depth)
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
            walkNode(sb, tree)

            return sb.toString().trimEnd('\n')
        }

        private fun walkNode(
            sb: StringBuilder,
            node: AccessibilityNodeData,
        ) {
            if (shouldKeepNode(node)) {
                val id = node.id
                val className = simplifyClassName(node.className)
                val text = sanitizeText(node.text)
                val desc = sanitizeText(node.contentDescription)
                val resId = sanitizeResourceId(node.resourceId)
                val bounds =
                    "${node.bounds.left},${node.bounds.top}," +
                        "${node.bounds.right},${node.bounds.bottom}"
                val flags = buildFlags(node)

                sb.appendLine(
                    "$id$SEP$className$SEP$text$SEP$desc$SEP$resId$SEP$bounds$SEP$flags",
                )
            }

            for (child in node.children) {
                walkNode(sb, child)
            }
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
         * 4. Truncates to [MAX_TEXT_LENGTH] characters with [TRUNCATION_SUFFIX] if exceeded.
         */
        internal fun sanitizeText(text: String?): String {
            val sanitized =
                text
                    ?.replace('\t', ' ')
                    ?.replace('\n', ' ')
                    ?.replace('\r', ' ')
                    ?.trim()
                    ?.ifEmpty { null }
            return when {
                sanitized == null -> NULL_VALUE
                sanitized.length > MAX_TEXT_LENGTH ->
                    sanitized.substring(0, MAX_TEXT_LENGTH) + TRUNCATION_SUFFIX
                else -> sanitized
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
            private const val SEP = "\t"
            const val COLUMN_SEPARATOR = "\t"
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
                "note:offscreen items require scroll_to_element before interaction"
            const val FLAG_ONSCREEN = "on"
            const val FLAG_OFFSCREEN = "off"
            const val FLAG_CLICKABLE = "clk"
            const val FLAG_LONG_CLICKABLE = "lclk"
            const val FLAG_FOCUSABLE = "foc"
            const val FLAG_SCROLLABLE = "scr"
            const val FLAG_EDITABLE = "edt"
            const val FLAG_ENABLED = "ena"
            private const val FLAG_SEPARATOR = ","
            const val HEADER =
                "id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}text${COLUMN_SEPARATOR}" +
                    "desc${COLUMN_SEPARATOR}res_id${COLUMN_SEPARATOR}bounds${COLUMN_SEPARATOR}flags"
        }
    }
