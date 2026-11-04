package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult

/**
 * Single entry point tools use to redact device-derived text/trees. Fail-closed: throws
 * [com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PrivacyModeUnavailable] when a
 * model-backed category is enabled but the model is not ready or inference fails; passthrough
 * (identity) when Privacy Mode is disabled.
 */
interface PrivacyPipeline {
    suspend fun processText(
        text: String,
        context: DetectionContext,
    ): String

    /** Same contract as [processText] for many items with a single model batch (packing amortization). */
    suspend fun processTexts(items: List<TextItem>): List<String>

    /** Redacts node text/contentDescription/action labels across all windows and returns the flagged node bounds. */
    suspend fun processTree(result: MultiWindowResult): ProcessedTree
}
