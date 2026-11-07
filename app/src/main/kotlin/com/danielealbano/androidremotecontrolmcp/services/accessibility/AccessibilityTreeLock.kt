package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Process-wide monitor that serializes access to the Android accessibility framework's node tree.
 *
 * The MCP server handles requests concurrently, and every fresh read first drops the shared
 * framework node cache ([AccessibilityServiceProvider.clearFrameworkNodeCache]) and then traverses
 * it (`getWindows`/`getRootInActiveWindow` + `getChild`/`refresh`) to repopulate live nodes. Without
 * serialization, one request's cache clear could wipe the cache mid-traversal of another request,
 * yielding a torn tree (parent metadata captured pre-clear, children re-fetched post-clear) and, via
 * the id→node cache populated from that torn snapshot, flaky node-id resolution for later actions.
 *
 * Every fresh-read choke point holds this monitor for the whole clear+read+populate critical section:
 * `getFreshRead`, which `getFreshWindows` wraps (get_screen_state / find_nodes / node action
 * re-reads / the type tools' reads), `rawNodeExists` (wait_for_node), `generateCurrentFingerprint`
 * (wait_for_idle), and `findFocusedEditableNode` (press_key / the type tools' input focus wait).
 * This satisfies the project rule that accessibility tree access MUST be serialized.
 *
 * A plain JVM monitor (not a coroutine `Mutex`) is used deliberately: these reads are synchronous,
 * blocking binder calls invoked from non-suspending functions, so `synchronized` matches the
 * existing execution model without forcing suspend signatures onto the read helpers or their
 * (partly non-suspending) unit tests. The monitor is only ever acquired alone — never nested with
 * another lock — so it cannot deadlock, and JVM monitors are reentrant on the same thread.
 */
internal object AccessibilityTreeLock {
    val monitor = Any()
}
