package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Immutable snapshot of a captured screen state, retained for cursor-based pagination of
 * get_screen_state. Holds only serializable data (no live AccessibilityNodeInfo references),
 * so it is safe to retain across calls.
 *
 * @property id Capture identifier: base36 of System.currentTimeMillis() at capture time.
 * @property result Parsed multi-window accessibility data captured at snapshot time.
 * @property screenInfo Screen dimensions/orientation captured at snapshot time.
 * @property totalKeptNodes Total number of kept TSV nodes across all windows.
 * @property totalPages Number of pages (ceil(totalKeptNodes / PAGE_SIZE)).
 * @property options Output options of the capture, applied to every page of it.
 */
data class ScreenStateSnapshot(
    val id: String,
    val result: MultiWindowResult,
    val screenInfo: ScreenInfo,
    val totalKeptNodes: Int,
    val totalPages: Int,
    val options: ScreenStateFormatOptions = ScreenStateFormatOptions(),
)

/**
 * Single-slot, thread-safe cache holding the most recent paginated screen-state snapshot.
 *
 * A new [store] replaces any previous snapshot. [get] returns the snapshot ONLY when its [id]
 * matches the currently stored snapshot; otherwise null (stale/replaced cursor). There is NO
 * change detection: a snapshot is invalidated only by a replacing [store] or by [clear].
 */
interface ScreenStateSnapshotCache {
    fun store(snapshot: ScreenStateSnapshot)

    fun get(id: String): ScreenStateSnapshot?

    fun clear()
}
