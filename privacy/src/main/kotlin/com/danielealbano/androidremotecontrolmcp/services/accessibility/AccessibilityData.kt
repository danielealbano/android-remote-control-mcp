package com.danielealbano.androidremotecontrolmcp.services.accessibility

import kotlinx.serialization.Serializable

/**
 * Represents the screen bounds of an accessibility node.
 */
@Serializable
data class BoundsData(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Represents a parsed accessibility node with all relevant properties.
 *
 * This is a JSON-serializable representation of [AccessibilityNodeInfo],
 * used by the `get_screen_state` MCP tool and element action tools.
 *
 * @property id Stable generated ID for this node, used by element action tools.
 * @property className The class name of the view (e.g., "android.widget.Button").
 * @property text The text content of the node.
 * @property contentDescription The content description for accessibility.
 * @property resourceId The view ID resource name (e.g., "com.example:id/button1").
 * @property bounds The screen bounds of the node.
 * @property clickable Whether the node responds to click actions.
 * @property longClickable Whether the node responds to long-click actions.
 * @property focusable Whether the node can receive focus.
 * @property scrollable Whether the node is scrollable.
 * @property editable Whether the node is an editable text field.
 * @property enabled Whether the node is enabled.
 * @property visible Whether the node is visible to the user.
 * @property webRole Chromium DOM role from `getExtras()` (e.g., "link", "heading", "article"),
 *   populated by Chrome and the Android System WebView on every web accessibility node. Null for
 *   native and Compose nodes. Used to scope WebView node collapsing to web content only.
 * @property targetUrl Target URL from `getExtras()` for links and images, or null when absent.
 * @property actions Labels of the node's accessibility actions that can be performed by label, in the node's
 *   order (custom ones such as a vote bar's "Upvote", and standard ones the app labelled).
 * @property children The child nodes of this node.
 */
@Serializable
data class AccessibilityNodeData(
    val id: String,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val bounds: BoundsData,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val isPassword: Boolean = false,
    val hintText: String? = null,
    val labeledByText: String? = null,
    val enabled: Boolean = false,
    val visible: Boolean = false,
    val webRole: String? = null,
    val targetUrl: String? = null,
    val actions: List<String> = emptyList(),
    val children: List<AccessibilityNodeData> = emptyList(),
)

/**
 * Represents a single window's parsed accessibility data with window metadata.
 *
 * @property windowId System-assigned unique window ID from [AccessibilityWindowInfo.getId].
 * @property windowType Window type label: APPLICATION, INPUT_METHOD, SYSTEM,
 *   ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY, or UNKNOWN.
 * @property packageName Package name of the window's root node, or null if unavailable.
 * @property title Window title (e.g., activity name, dialog title), or null if unavailable.
 * @property activityName Activity class name (best-effort, only for focused app window), or null.
 * @property layer Window layer (z-order from Android).
 * @property focused Whether this window currently has input focus.
 * @property tree The parsed accessibility node tree for this window.
 */
@Serializable
data class WindowData(
    val windowId: Int,
    val windowType: String,
    val packageName: String? = null,
    val title: String? = null,
    val activityName: String? = null,
    val layer: Int = 0,
    val focused: Boolean = false,
    val tree: AccessibilityNodeData,
)

/**
 * Result of multi-window accessibility tree parsing.
 *
 * @property windows List of parsed windows with metadata, ordered by z-order.
 * @property degraded True if the multi-window API was unavailable and the result
 *   fell back to single-window mode via [rootInActiveWindow]. When true, the output
 *   may not reflect all on-screen windows (e.g., system dialogs, permission popups).
 */
@Serializable
data class MultiWindowResult(
    val windows: List<WindowData>,
    val degraded: Boolean = false,
)
