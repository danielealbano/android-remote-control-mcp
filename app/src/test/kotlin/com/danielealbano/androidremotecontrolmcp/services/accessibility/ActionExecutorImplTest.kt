package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Field

@DisplayName("ActionExecutorImpl")
class ActionExecutorImplTest {
    private lateinit var executor: ActionExecutorImpl
    private lateinit var mockService: McpAccessibilityService
    private lateinit var mockCache: AccessibilityNodeCache
    private lateinit var mockTreeParser: AccessibilityTreeParser

    @BeforeEach
    fun setUp() {
        mockCache = mockk<AccessibilityNodeCache>(relaxed = true)
        mockTreeParser = mockk<AccessibilityTreeParser>(relaxed = true)
        executor = ActionExecutorImpl(mockCache, mockTreeParser)
        mockService = mockk<McpAccessibilityService>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        setServiceInstance(null)
    }

    /**
     * Uses reflection to set the McpAccessibilityService companion object's
     * instance field for testing purposes. Kotlin compiles companion object
     * properties as static fields on the enclosing class.
     */
    private fun setServiceInstance(service: McpAccessibilityService?) {
        val outerClass = McpAccessibilityService::class.java
        val instanceField: Field = outerClass.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, service)
    }

    private fun wrapInWindows(tree: AccessibilityNodeData): List<WindowData> =
        listOf(
            WindowData(
                windowId = 0,
                windowType = "APPLICATION",
                tree = tree,
                focused = true,
            ),
        )

    @Nested
    @DisplayName("Service availability")
    inner class ServiceAvailability {
        @Test
        @DisplayName("tap returns failure when service is not available")
        fun tapReturnsFailureWhenServiceNotAvailable() =
            runTest {
                // Arrange
                setServiceInstance(null)

                // Act
                val result = executor.tap(100f, 200f)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull() is IllegalStateException,
                )
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("not available") == true,
                )
            }

        @Test
        @DisplayName("pressBack returns failure when service is not available")
        fun pressBackReturnsFailureWhenServiceNotAvailable() =
            runTest {
                // Arrange
                setServiceInstance(null)

                // Act
                val result = executor.pressBack()

                // Assert
                assertTrue(result.isFailure)
            }

        @Test
        @DisplayName("clickNode returns failure when service is not available")
        fun clickNodeReturnsFailureWhenServiceNotAvailable() =
            runTest {
                // Arrange
                setServiceInstance(null)
                val tree =
                    AccessibilityNodeData(
                        id = "node_test",
                        bounds = BoundsData(0, 0, 100, 50),
                    )

                // Act
                val result = executor.clickNode("node_test", wrapInWindows(tree))

                // Assert
                assertTrue(result.isFailure)
            }
    }

    @Nested
    @DisplayName("Coordinate validation")
    inner class CoordinateValidation {
        @Test
        @DisplayName("tap rejects negative X coordinate")
        fun tapRejectsNegativeXCoordinate() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                // Act
                val result = executor.tap(-1f, 100f)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("tap rejects negative Y coordinate")
        fun tapRejectsNegativeYCoordinate() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                // Act
                val result = executor.tap(100f, -1f)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("swipe rejects negative start coordinates")
        fun swipeRejectsNegativeStartCoordinates() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                // Act
                val result = executor.swipe(-1f, 0f, 100f, 100f)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
    }

    @Nested
    @DisplayName("Global actions")
    inner class GlobalActions {
        @Test
        @DisplayName("pressBack calls performGlobalAction with GLOBAL_ACTION_BACK")
        fun pressBackCallsCorrectGlobalAction() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                every {
                    mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } returns true

                // Act
                val result = executor.pressBack()

                // Assert
                assertTrue(result.isSuccess)
                verify { mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            }

        @Test
        @DisplayName("pressHome calls performGlobalAction with GLOBAL_ACTION_HOME")
        fun pressHomeCallsCorrectGlobalAction() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                every {
                    mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                } returns true

                // Act
                val result = executor.pressHome()

                // Assert
                assertTrue(result.isSuccess)
                verify { mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            }

        @Test
        @DisplayName("pressRecents calls performGlobalAction with GLOBAL_ACTION_RECENTS")
        fun pressRecentsCallsCorrectGlobalAction() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                every {
                    mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                } returns true

                // Act
                val result = executor.pressRecents()

                // Assert
                assertTrue(result.isSuccess)
                verify { mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
            }

        @Test
        @DisplayName("global action returns failure when performGlobalAction returns false")
        fun globalActionReturnsFailureWhenActionFails() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                every {
                    mockService.performGlobalAction(any())
                } returns false

                // Act
                val result = executor.pressBack()

                // Assert
                assertTrue(result.isFailure)
            }
    }

    @Nested
    @DisplayName("Node actions")
    inner class NodeActions {
        @Test
        @DisplayName("clickNode returns failure when node is not found in tree")
        fun clickNodeReturnsFailureWhenNodeNotFound() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode.childCount } returns 0

                val mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo.id } returns 0
                every { mockWindowInfo.root } returns mockRootNode
                every { mockService.getAccessibilityWindows() } returns listOf(mockWindowInfo)

                val tree =
                    AccessibilityNodeData(
                        id = "node_root",
                        bounds = BoundsData(0, 0, 1080, 2400),
                    )

                // Act
                val result = executor.clickNode("node_nonexistent", wrapInWindows(tree))

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is NoSuchElementException)
            }
    }

    @Nested
    @DisplayName("Multi-window node actions")
    inner class MultiWindowNodeActions {
        @Test
        @DisplayName("clickNode finds and clicks node in first window")
        fun clickNodeFindsNodeInFirstWindow() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockRootNode1 = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode1.childCount } returns 0
                every { mockRootNode1.isClickable } returns true
                every { mockRootNode1.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

                val mockWindowInfo1 = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo1.id } returns 10
                every { mockWindowInfo1.root } returns mockRootNode1
                every { mockService.getAccessibilityWindows() } returns listOf(mockWindowInfo1)

                val tree1 =
                    AccessibilityNodeData(
                        id = "node_target",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 0, 100, 50),
                        clickable = true,
                    )

                val windows =
                    listOf(
                        WindowData(
                            windowId = 10,
                            windowType = "APPLICATION",
                            tree = tree1,
                            focused = true,
                        ),
                    )

                // Act
                val result = executor.clickNode("node_target", windows)

                // Assert
                assertTrue(result.isSuccess)
            }

        @Test
        @DisplayName("clickNode finds node in second window when not in first")
        fun clickNodeFindsNodeInSecondWindow() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                val mockRootNode1 = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode1.childCount } returns 0
                val mockWindowInfo1 = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo1.id } returns 10
                every { mockWindowInfo1.root } returns mockRootNode1

                val mockRootNode2 = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode2.childCount } returns 0
                every { mockRootNode2.isClickable } returns true
                every { mockRootNode2.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
                val mockWindowInfo2 = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo2.id } returns 20
                every { mockWindowInfo2.root } returns mockRootNode2

                every { mockService.getAccessibilityWindows() } returns
                    listOf(mockWindowInfo1, mockWindowInfo2)

                val tree1 =
                    AccessibilityNodeData(
                        id = "node_other",
                        bounds = BoundsData(0, 0, 100, 50),
                    )
                val tree2 =
                    AccessibilityNodeData(
                        id = "node_target",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 0, 200, 100),
                        clickable = true,
                    )

                val windows =
                    listOf(
                        WindowData(
                            windowId = 10,
                            windowType = "APPLICATION",
                            tree = tree1,
                            focused = true,
                        ),
                        WindowData(windowId = 20, windowType = "SYSTEM", tree = tree2),
                    )

                // Act
                val result = executor.clickNode("node_target", windows)

                // Assert
                assertTrue(result.isSuccess)
            }

        @Test
        @DisplayName("clickNode returns failure when node not found in any window")
        fun clickNodeReturnsFailureWhenNotFoundInAnyWindow() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                val mockRootNode1 = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode1.childCount } returns 0
                val mockWindowInfo1 = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo1.id } returns 10
                every { mockWindowInfo1.root } returns mockRootNode1

                val mockRootNode2 = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode2.childCount } returns 0
                val mockWindowInfo2 = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo2.id } returns 20
                every { mockWindowInfo2.root } returns mockRootNode2

                every { mockService.getAccessibilityWindows() } returns
                    listOf(mockWindowInfo1, mockWindowInfo2)

                val tree1 = AccessibilityNodeData(id = "node_a", bounds = BoundsData(0, 0, 100, 50))
                val tree2 = AccessibilityNodeData(id = "node_b", bounds = BoundsData(0, 0, 200, 100))

                val windows =
                    listOf(
                        WindowData(
                            windowId = 10,
                            windowType = "APPLICATION",
                            tree = tree1,
                            focused = true,
                        ),
                        WindowData(windowId = 20, windowType = "SYSTEM", tree = tree2),
                    )

                // Act
                val result = executor.clickNode("node_nonexistent", windows)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is NoSuchElementException)
            }
    }

    @Nested
    @DisplayName("Degraded-mode fallback")
    inner class DegradedModeFallback {
        @Test
        @DisplayName("clickNode succeeds in degraded mode when getAccessibilityWindows returns empty")
        fun clickNodeSucceedsInDegradedMode() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                every { mockService.getAccessibilityWindows() } returns emptyList()

                val mockFallbackRoot = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockFallbackRoot.childCount } returns 0
                every { mockFallbackRoot.isClickable } returns true
                every { mockFallbackRoot.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
                every { mockService.getRootNode() } returns mockFallbackRoot

                val tree =
                    AccessibilityNodeData(
                        id = "node_target",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 0, 100, 50),
                        clickable = true,
                    )

                // Act
                val result = executor.clickNode("node_target", wrapInWindows(tree))

                // Assert
                assertTrue(result.isSuccess)
            }
    }

    @Nested
    @DisplayName("Scroll coordinate-based gestures")
    inner class ScrollCoordinateGestures {
        // Test screen: 1080x2400, center=(540, 1200)
        private val testScreenInfo =
            ScreenInfo(
                width = 1080,
                height = 2400,
                densityDpi = 420,
                orientation = "portrait",
            )

        private lateinit var spyExecutor: ActionExecutorImpl

        @BeforeEach
        fun setUpScroll() {
            setServiceInstance(mockService)
            every { mockService.getScreenInfo() } returns testScreenInfo
            spyExecutor = spyk(executor)
            coEvery { spyExecutor.swipe(any(), any(), any(), any(), any()) } returns
                Result.success(Unit)
        }

        @Test
        @DisplayName("scroll calls getScreenInfo to obtain screen dimensions")
        fun scrollCallsGetScreenInfo() =
            runTest {
                // Act — use variancePercent=0f for deterministic test
                spyExecutor.scroll(ScrollDirection.UP, variancePercent = 0f)

                // Assert
                verify { mockService.getScreenInfo() }
            }

        @Test
        @DisplayName("scroll DOWN dispatches upward swipe (finger moves up to reveal content below)")
        fun scrollDownDispatchesUpwardSwipe() =
            runTest {
                // Act — MEDIUM=50%, scrollDistance=2400*0.5=1200, halfDistance=600
                val result = spyExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM, variancePercent = 0f)

                // Assert: finger starts at bottom (1800) and swipes to top (600)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(540f, 1800f, 540f, 600f, any())
                }
            }

        @Test
        @DisplayName("scroll UP dispatches downward swipe (finger moves down to reveal content above)")
        fun scrollUpDispatchesDownwardSwipe() =
            runTest {
                // Act — MEDIUM=50%, scrollDistance=2400*0.5=1200, halfDistance=600
                val result = spyExecutor.scroll(ScrollDirection.UP, ScrollAmount.MEDIUM, variancePercent = 0f)

                // Assert: finger starts at top (600) and swipes to bottom (1800)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(540f, 600f, 540f, 1800f, any())
                }
            }

        @Test
        @DisplayName("scroll LEFT dispatches rightward swipe (finger moves right to reveal content to the left)")
        fun scrollLeftDispatchesRightwardSwipe() =
            runTest {
                // Act — MEDIUM=50%, scrollDistance=1080*0.5=540, halfDistance=270
                val result = spyExecutor.scroll(ScrollDirection.LEFT, ScrollAmount.MEDIUM, variancePercent = 0f)

                // Assert: finger starts at left (270) and swipes to right (810)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(270f, 1200f, 810f, 1200f, any())
                }
            }

        @Test
        @DisplayName("scroll RIGHT dispatches leftward swipe (finger moves left to reveal content to the right)")
        fun scrollRightDispatchesLeftwardSwipe() =
            runTest {
                // Act — MEDIUM=50%, scrollDistance=1080*0.5=540, halfDistance=270
                val result = spyExecutor.scroll(ScrollDirection.RIGHT, ScrollAmount.MEDIUM, variancePercent = 0f)

                // Assert: finger starts at right (810) and swipes to left (270)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(810f, 1200f, 270f, 1200f, any())
                }
            }

        @Test
        @DisplayName("scroll DOWN with LARGE amount uses 75% of screen height")
        fun scrollDownWithLargeAmountUsesCorrectDistance() =
            runTest {
                // Act — LARGE=75%, scrollDistance=2400*0.75=1800, halfDistance=900
                val result = spyExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.LARGE, variancePercent = 0f)

                // Assert: finger starts at (540, 2100) swipes to (540, 300)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(540f, 2100f, 540f, 300f, any())
                }
            }

        @Test
        @DisplayName("scroll UP with SMALL amount uses 25% of screen height")
        fun scrollUpWithSmallAmountUsesCorrectDistance() =
            runTest {
                // Act — SMALL=25%, scrollDistance=2400*0.25=600, halfDistance=300
                val result = spyExecutor.scroll(ScrollDirection.UP, ScrollAmount.SMALL, variancePercent = 0f)

                // Assert: finger starts at (540, 900) swipes to (540, 1500)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(540f, 900f, 540f, 1500f, any())
                }
            }

        @Test
        @DisplayName("scroll LEFT with LARGE amount uses 75% of screen width")
        fun scrollLeftWithLargeAmountUsesCorrectDistance() =
            runTest {
                // Act — LARGE=75%, scrollDistance=1080*0.75=810, halfDistance=405
                val result = spyExecutor.scroll(ScrollDirection.LEFT, ScrollAmount.LARGE, variancePercent = 0f)

                // Assert: finger starts at (540-405=135) swipes to (540+405=945)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(135f, 1200f, 945f, 1200f, any())
                }
            }

        @Test
        @DisplayName("scroll RIGHT with SMALL amount uses 25% of screen width")
        fun scrollRightWithSmallAmountUsesCorrectDistance() =
            runTest {
                // Act — SMALL=25%, scrollDistance=1080*0.25=270, halfDistance=135
                val result = spyExecutor.scroll(ScrollDirection.RIGHT, ScrollAmount.SMALL, variancePercent = 0f)

                // Assert: finger starts at (540+135=675) swipes to (540-135=405)
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(675f, 1200f, 405f, 1200f, any())
                }
            }

        @Test
        @DisplayName("scroll with variance produces coordinates within expected bounds")
        fun scrollWithVarianceProducesCoordinatesWithinBounds() =
            runTest {
                // Arrange — capture actual swipe coordinates
                val x1Slot = slot<Float>()
                val y1Slot = slot<Float>()
                val x2Slot = slot<Float>()
                val y2Slot = slot<Float>()
                coEvery {
                    spyExecutor.swipe(
                        capture(x1Slot),
                        capture(y1Slot),
                        capture(x2Slot),
                        capture(y2Slot),
                        any(),
                    )
                } returns Result.success(Unit)

                // Act — 10% variance on 1080x2400 screen
                val result =
                    spyExecutor.scroll(
                        ScrollDirection.DOWN,
                        ScrollAmount.MEDIUM,
                        variancePercent = 0.10f,
                    )

                // Assert — all coordinates must be within screen bounds
                assertTrue(result.isSuccess)
                assertTrue(x1Slot.captured in 0f..1080f, "x1=${x1Slot.captured} out of screen bounds")
                assertTrue(y1Slot.captured in 0f..2400f, "y1=${y1Slot.captured} out of screen bounds")
                assertTrue(x2Slot.captured in 0f..1080f, "x2=${x2Slot.captured} out of screen bounds")
                assertTrue(y2Slot.captured in 0f..2400f, "y2=${y2Slot.captured} out of screen bounds")
            }

        @Test
        @DisplayName("scroll with zero variance produces exact center coordinates")
        fun scrollWithZeroVarianceProducesExactCenter() =
            runTest {
                // Act — 0% variance = deterministic, same as original behavior
                val result =
                    spyExecutor.scroll(
                        ScrollDirection.DOWN,
                        ScrollAmount.MEDIUM,
                        variancePercent = 0f,
                    )

                // Assert: exact center X for both endpoints
                assertTrue(result.isSuccess)
                coVerify {
                    spyExecutor.swipe(540f, 1800f, 540f, 600f, any())
                }
            }

        @Test
        @DisplayName("scroll propagates failure when swipe returns failure")
        fun scrollPropagatesSwipeFailure() =
            runTest {
                // Arrange — override the default success stub with a failure
                coEvery { spyExecutor.swipe(any(), any(), any(), any(), any()) } returns
                    Result.failure(RuntimeException("Gesture cancelled: swipe"))

                // Act
                val result = spyExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM, variancePercent = 0f)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is RuntimeException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("Gesture cancelled") == true,
                )
            }

        @Test
        @DisplayName("scroll returns failure when service is not available")
        fun scrollReturnsFailureWhenServiceNotAvailable() =
            runTest {
                // Arrange — override the @BeforeEach service instance with null
                setServiceInstance(null)

                // Act — use executor directly (not spyExecutor) since service is null
                val result = executor.scroll(ScrollDirection.DOWN)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("not available") == true,
                )
            }

        @Test
        @DisplayName("scroll with negative variancePercent throws IllegalArgumentException")
        fun scrollWithNegativeVariancePercentThrows() =
            runTest {
                assertThrows<IllegalArgumentException> {
                    spyExecutor.scroll(ScrollDirection.DOWN, variancePercent = -0.01f)
                }
            }

        @Test
        @DisplayName("scroll with variancePercent exceeding max throws IllegalArgumentException")
        fun scrollWithVariancePercentExceedingMaxThrows() =
            runTest {
                assertThrows<IllegalArgumentException> {
                    spyExecutor.scroll(
                        ScrollDirection.DOWN,
                        variancePercent = ActionExecutor.MAX_SCROLL_VARIANCE_PERCENT + 0.01f,
                    )
                }
            }
    }

    @Nested
    @DisplayName("Custom gesture validation")
    inner class CustomGestureValidation {
        @Test
        @DisplayName("customGesture returns failure for empty paths")
        fun customGestureReturnsFailureForEmptyPaths() =
            runTest {
                // Arrange
                setServiceInstance(mockService)

                // Act
                val result = executor.customGesture(emptyList())

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("customGesture returns failure for path with less than 2 points")
        fun customGestureReturnsFailureForShortPath() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val singlePointPath =
                    listOf(
                        listOf(GesturePoint(100f, 100f, 0L)),
                    )

                // Act
                val result = executor.customGesture(singlePointPath)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
    }

    private fun mockCacheHitWithIdentity(
        cachedNode: CachedNode,
        expectedNodeId: String,
        refreshResult: Boolean = true,
    ) {
        every { mockCache.get(expectedNodeId) } returns cachedNode
        every { cachedNode.node.refresh() } returns refreshResult
        if (refreshResult) {
            val rectSlot = slot<Rect>()
            every { cachedNode.node.getBoundsInScreen(capture(rectSlot)) } answers {
                rectSlot.captured.left = 0
                rectSlot.captured.top = 0
                rectSlot.captured.right = 100
                rectSlot.captured.bottom = 100
            }
            every {
                mockTreeParser.generateNodeId(
                    cachedNode.node,
                    any(),
                    cachedNode.depth,
                    cachedNode.index,
                    cachedNode.parentId,
                )
            } returns expectedNodeId
        }
    }

    @Nested
    @DisplayName("Cache hit")
    inner class CacheHit {
        @Test
        @DisplayName("clickNode uses valid cached node")
        fun clickNodeUsesValidCachedNode() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                mockCacheHitWithIdentity(cachedNode, "node_target")
                every { cachedNode.node.isClickable } returns true
                every { cachedNode.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

                // Act
                val result = executor.clickNode("node_target", emptyList())

                // Assert
                assertTrue(result.isSuccess)
                verify(exactly = 0) { mockService.getAccessibilityWindows() }
            }

        @Test
        @DisplayName("clickNode falls back when cache is stale")
        fun clickNodeFallsBackWhenCacheStale() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                every { mockCache.get("node_target") } returns cachedNode
                every { cachedNode.node.refresh() } returns false

                val mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode.childCount } returns 0
                every { mockRootNode.isClickable } returns true
                every { mockRootNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
                val mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo.id } returns 10
                every { mockWindowInfo.root } returns mockRootNode
                every { mockService.getAccessibilityWindows() } returns listOf(mockWindowInfo)

                val tree =
                    AccessibilityNodeData(
                        id = "node_target",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 0, 100, 50),
                        clickable = true,
                    )
                val windows =
                    listOf(
                        WindowData(windowId = 10, windowType = "APPLICATION", tree = tree, focused = true),
                    )

                // Act
                val result = executor.clickNode("node_target", windows)

                // Assert
                assertTrue(result.isSuccess)
                verify { mockService.getAccessibilityWindows() }
            }

        @Test
        @DisplayName("clickNode falls back when cache miss")
        fun clickNodeFallsBackWhenCacheMiss() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                every { mockCache.get("node_nonexistent") } returns null

                val mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode.childCount } returns 0
                val mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo.id } returns 0
                every { mockWindowInfo.root } returns mockRootNode
                every { mockService.getAccessibilityWindows() } returns listOf(mockWindowInfo)

                val tree =
                    AccessibilityNodeData(
                        id = "node_root",
                        bounds = BoundsData(0, 0, 1080, 2400),
                    )

                // Act
                val result = executor.clickNode("node_nonexistent", wrapInWindows(tree))

                // Assert
                assertTrue(result.isFailure)
                verify { mockService.getAccessibilityWindows() }
            }

        @Test
        @DisplayName("cached node is not recycled after use")
        fun cachedNodeNotRecycledAfterUse() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                mockCacheHitWithIdentity(cachedNode, "node_target")
                every { cachedNode.node.isClickable } returns true
                every { cachedNode.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

                // Act
                val result = executor.clickNode("node_target", emptyList())

                // Assert
                assertTrue(result.isSuccess)
                verify(exactly = 0) {
                    @Suppress("DEPRECATION")
                    cachedNode.node.recycle()
                }
            }

        @Test
        @DisplayName("clickNode fails when service unavailable even with cache")
        fun clickNodeFailsWhenServiceUnavailableEvenWithCache() =
            runTest {
                // Arrange
                setServiceInstance(null)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                every { mockCache.get("node_target") } returns cachedNode

                // Act
                val result = executor.clickNode("node_target", emptyList())

                // Assert
                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("not available") == true,
                )
                verify(exactly = 0) { mockCache.get(any()) }
            }

        @Test
        @DisplayName("scrollNode uses valid cached node")
        fun scrollNodeUsesValidCachedNode() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                mockCacheHitWithIdentity(cachedNode, "node_target")
                every { cachedNode.node.isScrollable } returns true
                every {
                    cachedNode.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } returns true

                // Act
                val result = executor.scrollNode("node_target", ScrollDirection.DOWN, emptyList())

                // Assert
                assertTrue(result.isSuccess)
                verify(exactly = 0) { mockService.getAccessibilityWindows() }
            }

        @Test
        @DisplayName("clickNode action fails via cached node")
        fun clickNodeActionFailsViaCachedNode() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                mockCacheHitWithIdentity(cachedNode, "node_target")
                every { cachedNode.node.isClickable } returns true
                every { cachedNode.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns false

                // Act
                val result = executor.clickNode("node_target", emptyList())

                // Assert
                assertTrue(result.isFailure)
                verify(exactly = 0) { mockService.getAccessibilityWindows() }
            }

        @Test
        @DisplayName("clickNode falls back when identity mismatch")
        fun clickNodeFallsBackWhenIdentityMismatch() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val cachedNode = CachedNode(mockNode, depth = 1, index = 0, parentId = "root")
                mockCacheHitWithIdentity(cachedNode, "node_target")
                // Override generateNodeId to return a different ID (identity mismatch)
                every {
                    mockTreeParser.generateNodeId(
                        cachedNode.node,
                        any(),
                        cachedNode.depth,
                        cachedNode.index,
                        cachedNode.parentId,
                    )
                } returns "node_DIFFERENT"

                val mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockRootNode.childCount } returns 0
                every { mockRootNode.isClickable } returns true
                every { mockRootNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
                val mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockWindowInfo.id } returns 10
                every { mockWindowInfo.root } returns mockRootNode
                every { mockService.getAccessibilityWindows() } returns listOf(mockWindowInfo)

                val tree =
                    AccessibilityNodeData(
                        id = "node_target",
                        className = "android.widget.Button",
                        bounds = BoundsData(0, 0, 100, 50),
                        clickable = true,
                    )
                val windows =
                    listOf(
                        WindowData(windowId = 10, windowType = "APPLICATION", tree = tree, focused = true),
                    )

                // Act
                val result = executor.clickNode("node_target", windows)

                // Assert
                assertTrue(result.isSuccess)
                verify { mockService.getAccessibilityWindows() }
            }
    }
}
