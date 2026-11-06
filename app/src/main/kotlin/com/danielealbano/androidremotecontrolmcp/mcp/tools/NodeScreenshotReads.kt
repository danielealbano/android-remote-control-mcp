package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.Edge
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollContainer
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollReach
import com.danielealbano.androidremotecontrolmcp.services.accessibility.cutEdges
import com.danielealbano.androidremotecontrolmcp.services.accessibility.fullSize

/** A scroll axis, with its start and end edges and its scroll directions (forward reveals what follows). */
internal enum class Axis(
    val startEdge: Edge,
    val endEdge: Edge,
    val forward: ScrollDirection,
    val backward: ScrollDirection,
) {
    VERTICAL(Edge.TOP, Edge.BOTTOM, ScrollDirection.DOWN, ScrollDirection.UP),
    HORIZONTAL(Edge.LEFT, Edge.RIGHT, ScrollDirection.RIGHT, ScrollDirection.LEFT),
    ;

    /** The start (top or left) of [bounds] on this axis. */
    fun start(bounds: BoundsData): Int = if (this == VERTICAL) bounds.top else bounds.left

    /** The extent of [bounds] on this axis. */
    fun extent(bounds: BoundsData): Int =
        if (this == VERTICAL) bounds.bottom - bounds.top else bounds.right - bounds.left

    /** The centre of [bounds] on this axis. */
    fun centre(bounds: BoundsData): Float =
        if (this == VERTICAL) (bounds.top + bounds.bottom) / 2f else (bounds.left + bounds.right) / 2f

    fun owns(edge: Edge): Boolean = edge == startEdge || edge == endEdge

    /** The scroll direction of this axis, forward or backward. */
    fun direction(forwards: Boolean): ScrollDirection = if (forwards) forward else backward
}

/** The centre of [this] bounds as (x, y). */
internal fun BoundsData.centre(): Pair<Float, Float> = (left + right) / 2f to (top + bottom) / 2f

/** A scrollable ancestor of a node in one read, with its live node and where it can still scroll. */
internal class Ancestor(
    val node: AccessibilityNodeInfo,
    val data: AccessibilityNodeData,
    val reach: ScrollReach,
) {
    /** Vertical when it can scroll up or down, else horizontal. */
    val axis: Axis = if (reach.up || reach.down) Axis.VERTICAL else Axis.HORIZONTAL

    val container: ScrollContainer = ScrollContainer(data.bounds, reach)
}

/**
 * One fresh read, indexed once: node data and pre-order position by id, and id by live node
 * ([AccessibilityNodeInfo.equals] is its source node plus its window, so a live node is found again in a later read).
 * [seq] counts the reads of the call, from 1.
 */
internal class ReadIndex(
    val read: FreshRead,
    val seq: Int,
) {
    private val dataById = HashMap<String, AccessibilityNodeData>()
    private val orderById = HashMap<String, Int>()
    private val idByNode = HashMap<AccessibilityNodeInfo, String>()

    init {
        read.result.windows.forEach { index(it.tree) }
        read.nodes.forEach { (id, cached) -> idByNode[cached.node] = id }
    }

    private fun index(node: AccessibilityNodeData) {
        dataById[node.id] = node
        orderById.putIfAbsent(node.id, orderById.size)
        node.children.forEach { index(it) }
    }

    fun data(id: String): AccessibilityNodeData? = dataById[id]

    fun node(id: String): AccessibilityNodeInfo? = read.nodes[id]?.node

    fun idOf(node: AccessibilityNodeInfo): String? = idByNode[node]

    /** The position of [id] by window, then tree pre-order. */
    fun order(id: String): Int = orderById[id] ?: Int.MAX_VALUE

    /** The scrollable ancestors of [id], nearest first, the node itself excluded. */
    fun scrollableAncestors(id: String): List<Ancestor> {
        val ancestors = mutableListOf<Ancestor>()
        var parent = read.nodes[id]?.parentId
        while (parent != null) {
            val cached = read.nodes[parent] ?: break
            val data = dataById[parent] ?: break
            if (data.scrollable) ancestors.add(Ancestor(cached.node, data, ScrollReach.of(cached.node)))
            parent = cached.parentId
        }
        return ancestors
    }

    /** The descendants of [id] matching [predicate]. */
    fun descendants(
        id: String,
        predicate: (AccessibilityNodeData) -> Boolean,
    ): List<AccessibilityNodeData> {
        val matches = mutableListOf<AccessibilityNodeData>()
        dataById[id]?.children?.forEach { collect(it, predicate, matches) }
        return matches
    }

    private fun collect(
        node: AccessibilityNodeData,
        predicate: (AccessibilityNodeData) -> Boolean,
        out: MutableList<AccessibilityNodeData>,
    ) {
        if (predicate(node)) out.add(node)
        node.children.forEach { collect(it, predicate, out) }
    }
}

/** Where a target is in a read. */
internal sealed interface Presence {
    data class Present(
        val id: String,
        val data: AccessibilityNodeData,
    ) : Presence

    data class Gone(
        val reason: GoneReason,
    ) : Presence
}

/** The last read where a target was present: its bounds and its scrollable ancestors, nearest first. */
internal class LastSeen(
    val readSeq: Int,
    val bounds: BoundsData,
    val containers: List<Ancestor>,
)

/**
 * A requested node found in the first read, followed through the later reads by its live [node]. Lists reuse rows, so
 * the live node only counts while it shows the same class, resource id, text and description as in the first read.
 */
internal class ShotTarget(
    val id: String,
    var node: AccessibilityNodeInfo,
    first: AccessibilityNodeData,
) {
    private val className = first.className
    private val resourceId = first.resourceId
    private val text = first.text
    private val contentDescription = first.contentDescription
    private val webRole = first.webRole

    var lastSeen: LastSeen? = null
        private set

    var outcome: ShotOutcome? = null

    /** Where the target is in [index], by its live node only. */
    fun presence(index: ReadIndex): Presence {
        val currentId = index.idOf(node)
        val data = currentId?.let { index.data(it) }
        return if (currentId != null && data != null) {
            if (sameItem(data)) Presence.Present(currentId, data) else Presence.Gone(GoneReason.CHANGED)
        } else {
            Presence.Gone(GoneReason.DISAPPEARED)
        }
    }

    /**
     * The target found again by content when its live node is not present: the single node with the same class,
     * resource id, text, description and web role among the descendants of its nearest last-seen container present in
     * [index] (found by its own live node), adopted as the target's live node. Compose gives a lazy-list item a new
     * id when it comes back into view, so its old live node is never found again. Null for no match or several.
     */
    fun reidentify(index: ReadIndex): Presence.Present? {
        val containerId = lastSeen?.containers?.firstNotNullOfOrNull { index.idOf(it.node) }
        val matches = containerId?.let { index.descendants(it, ::sameContent) }
        val data = matches?.singleOrNull()
        val live = data?.let { index.node(it.id) }
        return if (data != null && live != null) {
            node = live
            track(index)
            Presence.Present(data.id, data)
        } else {
            null
        }
    }

    /** Records [index] as the last read where the target was seen, when it is present there. */
    fun track(index: ReadIndex) {
        val present = presence(index) as? Presence.Present ?: return
        lastSeen = LastSeen(index.seq, present.data.bounds, index.scrollableAncestors(present.id))
    }

    private fun sameItem(data: AccessibilityNodeData): Boolean =
        data.className == className &&
            data.resourceId == resourceId &&
            data.text == text &&
            data.contentDescription == contentDescription

    private fun sameContent(data: AccessibilityNodeData): Boolean = sameItem(data) && data.webRole == webRole
}

/** How a present target looks in a read. */
internal sealed interface Judgement {
    data object OffScreen : Judgement

    data object Whole : Judgement

    /** Cut at [edges], each by the ancestor whose edge it touches. */
    data class Cut(
        val edges: Map<Edge, Ancestor>,
        val wording: CutWording,
    ) : Judgement
}

/**
 * Judges [present] in [index]. [movedTowardStart] is non-null only right after the target's own show, and it is
 * ignored for a web node: Chrome's show only makes a node's start visible, so a web node's cut stays after its show.
 */
internal fun judge(
    index: ReadIndex,
    present: Presence.Present,
    movedTowardStart: Set<Edge>?,
): Judgement {
    val data = present.data
    val bounds = data.bounds
    if (!data.visible || bounds.right <= bounds.left || bounds.bottom <= bounds.top) return Judgement.OffScreen
    val ancestors = index.scrollableAncestors(present.id)
    val containers = ancestors.map { it.container }
    val full = index.node(present.id)?.let { fullSize(it, data) }
    val justShown = movedTowardStart != null && data.webRole == null
    val moved = if (justShown) movedTowardStart.orEmpty() else emptySet()
    val edges = cutEdges(bounds, containers, full, justShown, moved)
    return if (edges.isEmpty()) {
        Judgement.Whole
    } else {
        Judgement.Cut(
            edges.mapValues { (_, container) -> ancestors[containers.indexOfFirst { it === container }] },
            if (full != null) CutWording.PARTIAL else CutWording.MAY_BE_PARTIAL,
        )
    }
}
