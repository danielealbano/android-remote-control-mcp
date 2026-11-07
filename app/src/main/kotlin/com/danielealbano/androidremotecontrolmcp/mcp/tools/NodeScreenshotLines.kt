package com.danielealbano.androidremotecontrolmcp.mcp.tools

/** The note heading the reply of a call that scrolled the app. */
internal const val SCROLLED_NOTE =
    "The app was scrolled to capture the nodes: node ids have changed, read the screen again before using any node id."

/** Why a node on screen got no image. */
internal const val NOT_SHOWN_REASON = "it could not be shown on screen"

/** The note of a node whose last part still ends at the edge of its container. */
internal const val CONTINUES_NOTE = "the node continues"

/** How a captured node's line says it may not be whole. */
internal enum class CutWording(
    val text: String,
) {
    /** The node's full size proves the cut (regular views). */
    PARTIAL("partial: cut by the edge of its scrolling container"),

    /** No full size to prove it (Compose and web nodes). */
    MAY_BE_PARTIAL("may be partial: it touches the edge of its scrolling container"),
}

/** Why a node acted on was lost while the app scrolled. */
internal enum class GoneReason(
    val reason: String,
) {
    DISAPPEARED("it disappeared while the app scrolled"),
    CHANGED("it changed while the app scrolled"),
}

/** What one requested node got. */
internal sealed interface ShotOutcome {
    /** Its images, in order. */
    val images: List<String> get() = emptyList()

    data object NotFound : ShotOutcome

    data object OffScreen : ShotOutcome

    data class NotCaptured(
        val reason: String,
    ) : ShotOutcome

    /**
     * One image, or the overlapping parts of a long node in order. [ending] is [CONTINUES_NOTE] or the note of a node
     * lost after its parts.
     */
    data class Captured(
        override val images: List<String>,
        val cut: CutWording?,
        val startNotReached: Boolean = false,
        val ending: String? = null,
    ) : ShotOutcome
}

/** The reply line of [id], whose images are numbered from [firstImage] (1-based, counting images only). */
internal fun shotLine(
    id: String,
    outcome: ShotOutcome,
    firstImage: Int,
): String =
    when (outcome) {
        ShotOutcome.NotFound -> "$id: not found"
        ShotOutcome.OffScreen -> "$id: not visible: off screen"
        is ShotOutcome.NotCaptured -> "$id: not captured: ${outcome.reason}"
        is ShotOutcome.Captured -> capturedLine(id, outcome, firstImage)
    }

/** The outcome of one [image] with its cut [wording]: not captured when its crop was empty. */
internal fun singleOutcome(
    image: String?,
    wording: CutWording?,
): ShotOutcome =
    when (image) {
        null -> ShotOutcome.NotCaptured(NOT_SHOWN_REASON)
        else -> ShotOutcome.Captured(listOf(image), wording)
    }

/** The note ending the parts of a node lost after them. */
internal fun lostNote(reason: GoneReason): String = "then ${reason.reason}"

private fun capturedLine(
    id: String,
    outcome: ShotOutcome.Captured,
    firstImage: Int,
): String {
    val count = outcome.images.size
    val images =
        if (count == 1) {
            "image $firstImage"
        } else {
            "images $firstImage-${firstImage + count - 1}: $count overlapping parts, in order"
        }
    return buildString {
        append("$id: $images")
        outcome.cut?.let { append(", ").append(it.text) }
        if (outcome.startNotReached) append("; its start was not reached")
        outcome.ending?.let { append("; ").append(it) }
    }
}
