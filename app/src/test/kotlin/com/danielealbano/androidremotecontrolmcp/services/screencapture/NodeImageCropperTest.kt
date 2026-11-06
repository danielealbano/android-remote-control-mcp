package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NodeImageCropper")
class NodeImageCropperTest {
    private val encoder = mockk<ScreenshotEncoder>()
    private val cropper = NodeImageCropper(encoder)

    @Nested
    @DisplayName("cropRect")
    inner class CropRectTests {
        @Test
        fun `screen bounds are scaled to the bitmap, outward to whole pixels`() {
            assertEquals(
                NodeImageCropper.CropRect(50, 100, 100, 101),
                cropper.cropRect(BoundsData(100, 200, 300, 401), 360, 800, 720, 1600),
            )
        }

        @Test
        fun `bounds past the bitmap are clamped to it`() {
            assertEquals(
                NodeImageCropper.CropRect(0, 1500, 720, 100),
                cropper.cropRect(BoundsData(-50, 1500, 900, 1700), 720, 1600, 720, 1600),
            )
        }

        @Test
        fun `nothing left on the bitmap gives no rect`() {
            assertNull(cropper.cropRect(BoundsData(0, 1600, 720, 1600), 720, 1600, 720, 1600))
            assertNull(cropper.cropRect(BoundsData(800, 0, 900, 100), 720, 1600, 720, 1600))
        }

        @Test
        fun `a screen side of zero gives no rect`() {
            assertNull(cropper.cropRect(BoundsData(0, 0, 100, 100), 720, 1600, 0, 1600))
            assertNull(cropper.cropRect(BoundsData(0, 0, 100, 100), 720, 1600, 720, 0))
        }
    }

    @Nested
    @DisplayName("crop")
    inner class CropTests {
        private val source = bitmap()
        private val data = ScreenshotData(data = "jpeg", width = 300, height = 200)

        private fun bitmap(): Bitmap =
            mockk(relaxed = true) {
                every { width } returns 720
                every { height } returns 1600
            }

        @BeforeEach
        fun setUp() {
            mockkStatic(Bitmap::class)
        }

        @AfterEach
        fun tearDown() {
            unmockkStatic(Bitmap::class)
        }

        @Test
        fun `a node off the bitmap gives no image and creates nothing`() {
            assertNull(cropper.crop(source, BoundsData(0, 1600, 720, 1600), 720, 1600))
            verify(exactly = 0) { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) }
        }

        @Test
        fun `a full-bitmap crop never recycles its input`() {
            val resized = bitmap()
            every { Bitmap.createBitmap(source, 0, 0, 720, 1600) } returns source
            every { encoder.resizeBitmapProportional(source, 700, 700) } returns resized
            every { encoder.bitmapToScreenshotData(resized, ScreenCaptureProvider.DEFAULT_QUALITY) } returns data

            assertEquals(data, cropper.crop(source, BoundsData(0, 0, 720, 1600), 720, 1600))

            verify(exactly = 0) { source.recycle() }
            verify(exactly = 1) { resized.recycle() }
        }

        @Test
        fun `a crop that needs no resize is recycled once and the input never`() {
            val cropped = bitmap()
            every { Bitmap.createBitmap(source, 0, 200, 720, 200) } returns cropped
            every { encoder.resizeBitmapProportional(cropped, 700, 700) } returns cropped
            every { encoder.bitmapToScreenshotData(cropped, ScreenCaptureProvider.DEFAULT_QUALITY) } returns data

            assertEquals(data, cropper.crop(source, BoundsData(0, 200, 720, 400), 720, 1600))

            verify(exactly = 1) { cropped.recycle() }
            verify(exactly = 0) { source.recycle() }
        }

        @Test
        fun `both intermediates are recycled and the input never`() {
            val cropped = bitmap()
            val resized = bitmap()
            every { Bitmap.createBitmap(source, 0, 200, 720, 1000) } returns cropped
            every { encoder.resizeBitmapProportional(cropped, 700, 700) } returns resized
            every { encoder.bitmapToScreenshotData(resized, ScreenCaptureProvider.DEFAULT_QUALITY) } returns data

            assertEquals(data, cropper.crop(source, BoundsData(0, 200, 720, 1200), 720, 1600))

            verify(exactly = 1) { cropped.recycle() }
            verify(exactly = 1) { resized.recycle() }
            verify(exactly = 0) { source.recycle() }
        }
    }
}
