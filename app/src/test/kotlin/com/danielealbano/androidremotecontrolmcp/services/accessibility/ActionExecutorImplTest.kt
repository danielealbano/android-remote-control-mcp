package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
                val result = executor.clickNode("node_test", tree)

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
                every { mockService.getRootNode() } returns mockRootNode
                every { mockRootNode.childCount } returns 0

                val rectSlot = slot<Rect>()
                every { mockRootNode.getBoundsInScreen(capture(rectSlot)) } answers {
                    rectSlot.captured.left = 0
                    rectSlot.captured.top = 0
                    rectSlot.captured.right = 1080
                    rectSlot.captured.bottom = 2400
                }

                val tree =
                    AccessibilityNodeData(
                        id = "node_root",
                        bounds = BoundsData(0, 0, 1080, 2400),
                    )

                // Act
                val result = executor.clickNode("node_nonexistent", tree)

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is NoSuchElementException)
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
