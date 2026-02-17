package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ScreenshotAnnotatorTest")
class ScreenshotAnnotatorTest {
    private val annotator = ScreenshotAnnotator()

    @Suppress("LongParameterList")
    private fun makeNode(
        id: String = "node_test",
        className: String = "android.widget.Button",
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: BoundsData = BoundsData(0, 0, 100, 100),
        visible: Boolean = true,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        focusable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = false,
        children: List<AccessibilityNodeData> = emptyList(),
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = id,
            className = className,
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            bounds = bounds,
            visible = visible,
            clickable = clickable,
            longClickable = longClickable,
            focusable = focusable,
            scrollable = scrollable,
            editable = editable,
            enabled = enabled,
            children = children,
        )

    @Nested
    @DisplayName("extractLabel")
    inner class ExtractLabelTests {
        @Test
        fun `strips node_ prefix`() {
            assertEquals("a3f2", annotator.extractLabel("node_a3f2"))
        }

        @Test
        fun `returns full id when no prefix`() {
            assertEquals("custom_id", annotator.extractLabel("custom_id"))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", annotator.extractLabel(""))
        }

        @Test
        fun `handles node_ prefix only (empty hash)`() {
            assertEquals("", annotator.extractLabel("node_"))
        }
    }

    @Nested
    @DisplayName("computeScaledBounds")
    inner class ComputeScaledBoundsTests {
        @Test
        fun `correctly scales coordinates`() {
            // Screen 1080x2400, bitmap 540x1200 → scaleX=0.5, scaleY=0.5
            val element = makeNode(bounds = BoundsData(100, 200, 300, 400))
            val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
            assertNotNull(result)
            assertEquals(50f, result!!.left)
            assertEquals(100f, result.top)
            assertEquals(150f, result.right)
            assertEquals(200f, result.bottom)
        }

        @Test
        fun `returns null for element fully outside bitmap`() {
            val element = makeNode(bounds = BoundsData(2000, 3000, 2100, 3100))
            val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
            assertNull(result)
        }

        @Test
        fun `clamps partially outside element`() {
            val element = makeNode(bounds = BoundsData(1000, 2300, 1200, 2600))
            val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
            assertNotNull(result)
            assertEquals(500f, result!!.left)
            assertEquals(540f, result.right) // clamped to bitmapWidth
        }

        @Test
        fun `returns null when zero-width element maps to same edge`() {
            // Element with left == right at screen edge
            val element = makeNode(bounds = BoundsData(1080, 0, 1080, 100))
            val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
            assertNull(result)
        }

        @Test
        fun `returns null when non-zero-width element collapses after clamping`() {
            // Element has width (1080 to 1200) but after scale (0.5) both map to 540+
            // and clamp to bitmapWidth=540, resulting in left==right==540
            val element = makeNode(bounds = BoundsData(1080, 0, 1200, 100))
            val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("annotate")
    inner class AnnotateTests {
        private lateinit var mockBitmap: Bitmap
        private lateinit var mockCopy: Bitmap

        @BeforeEach
        fun setup() {
            mockBitmap = mockk(relaxed = true)
            mockCopy = mockk(relaxed = true)
            every { mockBitmap.width } returns 540
            every { mockBitmap.height } returns 1200
            // annotate() uses ARGB_8888 unconditionally (not bitmap.config)
            every { mockBitmap.copy(Bitmap.Config.ARGB_8888, true) } returns mockCopy
            every { mockCopy.width } returns 540
            every { mockCopy.height } returns 1200
            mockkConstructor(Canvas::class)
            every { anyConstructed<Canvas>().drawRoundRect(any(), any(), any(), any()) } just Runs
            every { anyConstructed<Canvas>().drawRect(any<RectF>(), any()) } just Runs
            every { anyConstructed<Canvas>().drawText(any(), any(), any(), any()) } just Runs
        }

        @AfterEach
        fun teardown() {
            unmockkConstructor(Canvas::class)
        }

        @Test
        fun `empty elements returns copy without draw calls`() {
            val result = annotator.annotate(mockBitmap, emptyList(), 1080, 2400)
            assertEquals(mockCopy, result)
            verify(exactly = 0) { mockCopy.recycle() } // input not recycled
        }

        @Test
        fun `single element draws box and label`() {
            val element = makeNode(id = "node_a3f2", bounds = BoundsData(100, 200, 300, 400), visible = true)
            annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
            // Verify at least one drawRect and one drawText were called
            verify(atLeast = 1) { anyConstructed<Canvas>().drawRect(any<RectF>(), any()) }
            verify(atLeast = 1) {
                anyConstructed<Canvas>().drawText(match { it.contains("a3f2") }, any(), any(), any())
            }
        }

        @Test
        fun `does not mutate input bitmap`() {
            val element = makeNode(id = "node_x1y2", bounds = BoundsData(100, 200, 300, 400), visible = true)
            val result = annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
            assertNotSame(mockBitmap, result)
            assertEquals(mockCopy, result)
        }

        @Test
        fun `recycles copy bitmap when drawing fails`() {
            // Simulate Canvas drawing throwing
            every { anyConstructed<Canvas>().drawRect(any<RectF>(), any()) } throws
                RuntimeException("Draw failed")
            val element = makeNode(id = "node_fail", bounds = BoundsData(100, 200, 300, 400), visible = true)

            var thrown = false
            try {
                annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
            } catch (_: RuntimeException) {
                thrown = true
            }
            assertTrue(thrown, "Should have thrown RuntimeException")
            // The copy bitmap should be recycled despite the exception
            verify(exactly = 1) { mockCopy.recycle() }
        }

        @Test
        fun `throws IllegalStateException when bitmap copy returns null`() {
            every { mockBitmap.copy(Bitmap.Config.ARGB_8888, true) } returns null
            val element = makeNode(id = "node_test", bounds = BoundsData(100, 200, 300, 400), visible = true)

            val exception =
                org.junit.jupiter.api.assertThrows<IllegalStateException> {
                    annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
                }
            assertTrue(exception.message!!.contains("Failed to create mutable bitmap copy"))
        }

        @Test
        fun `throws IllegalStateException when bitmap copy returns null for empty elements`() {
            every { mockBitmap.copy(Bitmap.Config.ARGB_8888, true) } returns null

            val exception =
                org.junit.jupiter.api.assertThrows<IllegalStateException> {
                    annotator.annotate(mockBitmap, emptyList(), 1080, 2400)
                }
            assertTrue(exception.message!!.contains("Failed to create mutable bitmap copy"))
        }
    }
}
