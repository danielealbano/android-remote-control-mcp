package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Output options of `get_screen_state`. The defaults give the full output, unchanged.
 *
 * @property includeOffscreen When false, nodes that are not on screen (flag `off`) are not listed: no TSV row and no
 *   hierarchy line. Their on-screen descendants are still listed, nested under the nearest listed ancestor, the same
 *   way the children of structural-only nodes are.
 * @property includeHierarchy When false, the `hierarchy:` block of every window is left out.
 */
data class ScreenStateFormatOptions(
    val includeOffscreen: Boolean = true,
    val includeHierarchy: Boolean = true,
)
