package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

@DisplayName("ActionExecutorImpl")
class ActionExecutorImplTest {
    private lateinit var executor: ActionExecutorImpl
    private lateinit var mockService: McpAccessibilityService

    @BeforeEach
    fun setUp() {
        executor = ActionExecutorImpl()
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
    @DisplayName("Scroll with getScreenInfo")
    inner class ScrollWithScreenInfo {
        @Test
        @DisplayName("scroll uses getScreenInfo for screen dimensions")
        fun scrollUsesGetScreenInfo() =
            runTest {
                // Arrange
                setServiceInstance(mockService)
                val screenInfo =
                    ScreenInfo(
                        width = 1080,
                        height = 2400,
                        densityDpi = 420,
                        orientation = "portrait",
                    )
                every { mockService.getScreenInfo() } returns screenInfo
                every { mockService.dispatchGesture(any(), any(), any()) } returns true

                // Act
                val result = executor.scroll(0f, -500f)

                // Assert
                assertTrue(result.isSuccess)
                verify { mockService.getScreenInfo() }
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
}
