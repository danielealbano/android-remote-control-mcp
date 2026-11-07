package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.privacy.ProcessedTree
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.DragSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollReach
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.accessibility.mockAction
import com.danielealbano.androidremotecontrolmcp.services.screencapture.NodeImageCropper
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotRedactor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/** The phone the scripted screens are drawn on. */
internal val FAKE_SCREEN = ScreenInfo(720, 1600, 320, ScreenInfo.ORIENTATION_PORTRAIT)

internal fun reach(
    up: Boolean = false,
    down: Boolean = false,
    left: Boolean = false,
    right: Boolean = false,
): ScrollReach = ScrollReach(up = up, down = down, left = left, right = right)

/**
 * A node of a scripted screen: scrollable when [reach] is not null (its directional scroll actions), a regular view
 * with [fullSize] (`getBoundsInParent`), otherwise reporting 0x0 as Compose does. [live] names its live node: the same
 * name is the same [AccessibilityNodeInfo] on every screen, a new one is a new instance.
 */
internal class FakeNode(
    val name: String,
    val bounds: BoundsData,
    val children: List<FakeNode> = emptyList(),
    val reach: ScrollReach? = null,
    val fullSize: Pair<Int, Int>? = null,
    val webRole: String? = null,
    val text: String = name,
    val live: String = name,
    val visible: Boolean = bounds.right > bounds.left && bounds.bottom > bounds.top,
)

/** [raw] clipped to [container], collapsed onto the container edge it lies beyond when nothing of it is inside. */
internal fun clip(
    raw: BoundsData,
    container: BoundsData,
): BoundsData {
    val left = maxOf(raw.left, container.left)
    val right = minOf(raw.right, container.right)
    val top = raw.top.coerceIn(container.top, container.bottom)
    val bottom = raw.bottom.coerceIn(container.top, container.bottom)
    return if (right > left && bottom > top) BoundsData(left, top, right, bottom) else BoundsData(left, top, right, top)
}

/**
 * A scripted device for the node screenshot session. The first read sees the first of [screens] (the root of its only
 * window); every show and every dispatched drag moves to the next, the last one staying. Node ids are the node names
 * on the first screen and change on every later one, as ids do once anything moves. Reads go through [getFreshRead]:
 * the tree parser fills the node map from the current screen.
 */
internal class FakeDevice(
    vararg screens: FakeNode,
) {
    private val script = screens.toList()
    private val liveNodes = HashMap<String, AccessibilityNodeInfo>()

    var step = 0
        private set
    var reads = 0
        private set

    val drags = mutableListOf<DragSegment>()
    val shows = mutableListOf<String>()
    val bitmaps = mutableListOf<Bitmap>()
    val copies = mutableListOf<Bitmap>()
    val maskedWith = mutableListOf<List<BoundsData>>()

    /** The bitmaps masking made, one per capture with flagged bounds. */
    val maskedBitmaps = mutableListOf<Bitmap>()

    /** The read count at each screenshot. */
    val capturedAtRead = mutableListOf<Int>()

    /** The results of the drags in order; drags past the list complete. */
    val dragResults = mutableListOf<Result<Unit>>()

    /** Nodes (by name) whose crop is empty. */
    val emptyCrops = mutableSetOf<String>()

    /** The bounds Privacy Mode flags in the tree read on each screen step (told apart by the id of its root). */
    val flagged = HashMap<Int, List<BoundsData>>()

    var bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888

    val treeParser = mockk<AccessibilityTreeParser>()
    val accessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
    val actionExecutor = mockk<ActionExecutor>()
    val screenCaptureProvider = mockk<ScreenCaptureProvider>()
    val screenshotRedactor = mockk<ScreenshotRedactor>()
    val nodeImageCropper = mockk<NodeImageCropper>()
    val privacyToolGate = mockk<PrivacyToolGate>()

    val deps =
        NodeScreenshotDeps(
            treeParser = treeParser,
            accessibilityServiceProvider = accessibilityServiceProvider,
            nodeCache = mockk<AccessibilityNodeCache>(relaxed = true),
            actionExecutor = actionExecutor,
            screenCaptureProvider = screenCaptureProvider,
            screenshotRedactor = screenshotRedactor,
            nodeImageCropper = nodeImageCropper,
            webViewNodeMerger = WebViewNodeMerger(),
            privacyToolGate = privacyToolGate,
        )

    private val root = mockk<AccessibilityNodeInfo>(relaxed = true)

    // Every mock is created and stubbed up front: stubbing inside an answer is not reliable.
    private val scrollUp = mockAction(android.R.id.accessibilityActionScrollUp, null)
    private val scrollDown = mockAction(android.R.id.accessibilityActionScrollDown, null)
    private val scrollLeft = mockAction(android.R.id.accessibilityActionScrollLeft, null)
    private val scrollRight = mockAction(android.R.id.accessibilityActionScrollRight, null)

    init {
        script.forEach { collectLives(it) }
        stubReads()
        stubDrags()
        stubCaptures()
    }

    /** The id of [name] as read on screen [atStep]. */
    fun id(
        name: String,
        atStep: Int = 0,
    ): String = if (atStep == 0) name else "$name@$atStep"

    fun session(): NodeScreenshotSession = NodeScreenshotSession(deps)

    /** The live node named [key]. */
    fun live(key: String): AccessibilityNodeInfo = liveNodes.getOrPut(key) { mockLive(key) }

    private fun current(): FakeNode = script[step]

    private fun advance() {
        step = (step + 1).coerceAtMost(script.lastIndex)
    }

    private fun find(
        node: FakeNode,
        predicate: (FakeNode) -> Boolean,
    ): FakeNode? = if (predicate(node)) node else node.children.firstNotNullOfOrNull { find(it, predicate) }

    /** The deepest node of the current screen with exactly [bounds]. */
    private fun nameAt(bounds: BoundsData): String {
        var found = "?"

        fun walk(node: FakeNode) {
            if (node.bounds == bounds) found = node.name
            node.children.forEach { walk(it) }
        }
        walk(current())
        return found
    }

    private fun mockLive(key: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        val here = { find(current()) { it.live == key } }
        every { node.refresh() } answers { here() != null }
        every { node.getBoundsInScreen(any()) } answers {
            here()?.bounds?.let { setRect(firstArg(), it.left, it.top, it.right, it.bottom) }
        }
        // The deprecated getter is the only source of a view's full size (approved for NodeCutRule's fullSize).
        @Suppress("DEPRECATION")
        every { node.getBoundsInParent(any()) } answers {
            here()?.fullSize?.let { (width, height) -> setRect(firstArg(), 0, 0, width, height) }
        }
        every { node.actionList } answers { here()?.reach?.let { actionsOf(it) }.orEmpty() }
        every { node.collectionInfo } returns null
        every { node.performAction(android.R.id.accessibilityActionShowOnScreen) } answers {
            shows.add(key)
            advance()
            true
        }
        return node
    }

    private fun setRect(
        rect: Rect,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        rect.left = left
        rect.top = top
        rect.right = right
        rect.bottom = bottom
    }

    private fun actionsOf(reach: ScrollReach): List<AccessibilityNodeInfo.AccessibilityAction> =
        listOfNotNull(
            scrollUp.takeIf { reach.up },
            scrollDown.takeIf { reach.down },
            scrollLeft.takeIf { reach.left },
            scrollRight.takeIf { reach.right },
        )

    private fun collectLives(node: FakeNode) {
        live(node.live)
        node.children.forEach { collectLives(it) }
    }

    private fun build(
        node: FakeNode,
        depth: Int,
        index: Int,
        parentId: String,
        out: MutableMap<String, CachedNode>,
    ): AccessibilityNodeData {
        val nodeId = id(node.name, step)
        out[nodeId] = CachedNode(live(node.live), depth, index, parentId)
        return AccessibilityNodeData(
            id = nodeId,
            className = "android.view.View",
            text = node.text,
            bounds = node.bounds,
            scrollable = node.reach != null,
            visible = node.visible,
            webRole = node.webRole,
            children = node.children.mapIndexed { i, child -> build(child, depth + 1, i, nodeId, out) },
        )
    }

    private fun stubReads() {
        val window = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { window.id } returns WINDOW_ID
        every { window.root } returns root
        every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { window.isFocused } returns true
        every { accessibilityServiceProvider.isReady() } returns true
        every { accessibilityServiceProvider.getScreenInfo() } returns FAKE_SCREEN
        every { accessibilityServiceProvider.getAccessibilityWindows() } returns listOf(window)
        every { treeParser.parseTree(root, "root_w$WINDOW_ID", any()) } answers {
            reads++
            build(current(), 0, 0, "root_w$WINDOW_ID", thirdArg())
        }
    }

    private fun stubDrags() {
        coEvery { actionExecutor.dragAndHold(any(), any(), any(), any()) } answers {
            drags.add(DragSegment(firstArg(), secondArg(), thirdArg(), arg(3)))
            val result = dragResults.getOrNull(drags.size - 1) ?: Result.success(Unit)
            val error = result.exceptionOrNull()
            if (error !is IllegalArgumentException && error !is IllegalStateException) advance()
            result
        }
    }

    private fun stubCaptures() {
        every { screenCaptureProvider.isScreenCaptureAvailable() } returns true
        // Stubbed up front: stubbing inside an answer is not reliable.
        val pool =
            List(BITMAP_POOL) {
                val bitmap = mockk<Bitmap>(relaxed = true)
                val copy = mockk<Bitmap>(relaxed = true)
                every { bitmap.config } answers { bitmapConfig }
                every { bitmap.copy(Bitmap.Config.ARGB_8888, false) } answers {
                    copies.add(copy)
                    copy
                }
                bitmap
            }
        coEvery { screenCaptureProvider.captureScreenshotBitmap(null, null) } answers {
            val bitmap = pool[bitmaps.size]
            bitmaps.add(bitmap)
            capturedAtRead.add(reads)
            Result.success(bitmap)
        }
        coEvery { privacyToolGate.tree(any()) } answers {
            val tree = firstArg<MultiWindowResult>()
            val rootId =
                tree.windows
                    .single()
                    .tree.id
            val readStep = rootId.substringAfter('@', "0").toInt()
            ProcessedTree(tree, flagged[readStep].orEmpty())
        }
        // As ScreenshotRedactor: the capture itself when nothing is flagged, a new mutable bitmap otherwise.
        val maskPool = List(BITMAP_POOL) { mockk<Bitmap>(relaxed = true) }
        maskPool.forEach { every { it.config } returns Bitmap.Config.ARGB_8888 }
        every { screenshotRedactor.mask(any(), any(), any(), any()) } answers {
            val bounds = secondArg<List<BoundsData>>()
            maskedWith.add(bounds)
            if (bounds.isEmpty()) {
                firstArg()
            } else {
                maskPool[maskedBitmaps.size].also { maskedBitmaps.add(it) }
            }
        }
        every { nodeImageCropper.crop(any(), any(), any(), any()) } answers {
            val name = nameAt(secondArg())
            if (name in emptyCrops) null else ScreenshotData(data = "c${bitmaps.size}/$name", width = 1, height = 1)
        }
    }

    companion object {
        const val WINDOW_ID = 7
        private const val BITMAP_POOL = 12
    }
}
