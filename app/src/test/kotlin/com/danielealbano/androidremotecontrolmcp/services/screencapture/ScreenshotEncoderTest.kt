package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.media.Image
import android.util.Base64
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.nio.ByteBuffer

@DisplayName("ScreenshotEncoder")
class ScreenshotEncoderTest {
    private lateinit var encoder: ScreenshotEncoder

    @BeforeEach
    fun setUp() {
        encoder = ScreenshotEncoder()
        mockkStatic(Bitmap::class)
        mockkStatic(Base64::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Bitmap::class)
        unmockkStatic(Base64::class)
    }

    @Nested
    @DisplayName("imageToBitmap")
    inner class ImageToBitmapTests {
        @Test
        @DisplayName("converts image without row padding to bitmap of correct dimensions")
        fun `converts image without row padding`() {
            // Arrange
            val width = 100
            val height = 50
            val pixelStride = 4
            val rowStride = width * pixelStride

            val buffer = ByteBuffer.allocate(rowStride * height)
            val plane =
                mockk<Image.Plane> {
                    every { getBuffer() } returns buffer
                    every { getPixelStride() } returns pixelStride
                    every { getRowStride() } returns rowStride
                }
            val image =
                mockk<Image> {
                    every { planes } returns arrayOf(plane)
                    every { getWidth() } returns width
                    every { getHeight() } returns height
                }

            val bitmap =
                mockk<Bitmap>(relaxed = true) {
                    every { getWidth() } returns width
                    every { getHeight() } returns height
                }
            every { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } returns bitmap

            // Act
            val result = encoder.imageToBitmap(image)

            // Assert
            assertEquals(bitmap, result)
            verify { bitmap.copyPixelsFromBuffer(buffer) }
        }

        @Test
        @DisplayName("rewinds buffer before copying pixels to handle non-zero buffer position")
        fun `rewinds buffer before copying`() {
            // Arrange
            val width = 10
            val height = 5
            val pixelStride = 4
            val rowStride = width * pixelStride

            val buffer = ByteBuffer.allocate(rowStride * height)
            buffer.position(100) // Simulate non-zero position from upstream usage
            val plane =
                mockk<Image.Plane> {
                    every { getBuffer() } returns buffer
                    every { getPixelStride() } returns pixelStride
                    every { getRowStride() } returns rowStride
                }
            val image =
                mockk<Image> {
                    every { planes } returns arrayOf(plane)
                    every { getWidth() } returns width
                    every { getHeight() } returns height
                }

            val bitmap = mockk<Bitmap>(relaxed = true)
            every { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } returns bitmap

            // Act
            encoder.imageToBitmap(image)

            // Assert - buffer position should be 0 after rewind (mocked copyPixelsFromBuffer
            // does not advance it)
            assertEquals(0, buffer.position(), "Buffer should be rewound to position 0")
        }

        @Test
        @DisplayName("converts image with row padding by cropping to correct dimensions")
        fun `converts image with row padding`() {
            // Arrange
            val width = 100
            val height = 50
            val pixelStride = 4
            val rowPadding = 16
            val rowStride = width * pixelStride + rowPadding

            val buffer = ByteBuffer.allocate(rowStride * height)
            val plane =
                mockk<Image.Plane> {
                    every { getBuffer() } returns buffer
                    every { getPixelStride() } returns pixelStride
                    every { getRowStride() } returns rowStride
                }
            val image =
                mockk<Image> {
                    every { planes } returns arrayOf(plane)
                    every { getWidth() } returns width
                    every { getHeight() } returns height
                }

            val bitmapWidth = width + rowPadding / pixelStride
            val paddedBitmap = mockk<Bitmap>(relaxed = true)
            val croppedBitmap =
                mockk<Bitmap>(relaxed = true) {
                    every { getWidth() } returns width
                    every { getHeight() } returns height
                }
            every { Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888) } returns paddedBitmap
            every { Bitmap.createBitmap(paddedBitmap, 0, 0, width, height) } returns croppedBitmap

            // Act
            val result = encoder.imageToBitmap(image)

            // Assert
            assertEquals(croppedBitmap, result)
            verify { paddedBitmap.recycle() }
        }
    }

    @Nested
    @DisplayName("encodeBitmapToJpeg")
    inner class EncodeBitmapToJpegTests {
        @Test
        @DisplayName("compresses bitmap to JPEG with specified quality")
        fun `compresses bitmap to JPEG`() {
            // Arrange
            val bitmap = mockk<Bitmap>(relaxed = true)
            val qualitySlot = slot<Int>()
            every {
                bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any<OutputStream>())
            } returns true

            // Act
            encoder.encodeBitmapToJpeg(bitmap, 80)

            // Assert
            assertEquals(80, qualitySlot.captured)
        }

        @Test
        @DisplayName("clamps quality below minimum to 1")
        fun `clamps quality below minimum`() {
            // Arrange
            val bitmap = mockk<Bitmap>(relaxed = true)
            val qualitySlot = slot<Int>()
            every {
                bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any<OutputStream>())
            } returns true

            // Act
            encoder.encodeBitmapToJpeg(bitmap, 0)

            // Assert
            assertEquals(1, qualitySlot.captured)
        }

        @Test
        @DisplayName("clamps quality above maximum to 100")
        fun `clamps quality above maximum`() {
            // Arrange
            val bitmap = mockk<Bitmap>(relaxed = true)
            val qualitySlot = slot<Int>()
            every {
                bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any<OutputStream>())
            } returns true

            // Act
            encoder.encodeBitmapToJpeg(bitmap, 150)

            // Assert
            assertEquals(100, qualitySlot.captured)
        }

        @Test
        @DisplayName("throws IllegalStateException when bitmap compression fails")
        fun `throws on compression failure`() {
            // Arrange
            val bitmap = mockk<Bitmap>(relaxed = true)
            every {
                bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any<OutputStream>())
            } returns false

            // Act & Assert
            val exception =
                assertThrows(IllegalStateException::class.java) {
                    encoder.encodeBitmapToJpeg(bitmap, 80)
                }
            assertEquals("Failed to compress bitmap to JPEG", exception.message)
        }

        @Test
        @DisplayName("low quality produces smaller output than high quality for same bitmap")
        fun `quality affects output size`() {
            // Arrange
            val bitmap = mockk<Bitmap>(relaxed = true)
            val lowQualityBytes = byteArrayOf(1, 2, 3)
            val highQualityBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

            every {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 10, any<OutputStream>())
            } answers {
                thirdArg<OutputStream>().write(lowQualityBytes)
                true
            }
            every {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, any<OutputStream>())
            } answers {
                thirdArg<OutputStream>().write(highQualityBytes)
                true
            }

            // Act
            val lowResult = encoder.encodeBitmapToJpeg(bitmap, 10)
            val highResult = encoder.encodeBitmapToJpeg(bitmap, 95)

            // Assert
            assertTrue(
                lowResult.size < highResult.size,
                "Low quality (${lowResult.size} bytes) should be smaller than high quality (${highResult.size} bytes)",
            )
        }
    }

    @Nested
    @DisplayName("encodeToBase64")
    inner class EncodeToBase64Tests {
        @Test
        @DisplayName("encodes byte array to base64 string with NO_WRAP flag")
        fun `encodes to base64 with NO_WRAP`() {
            // Arrange
            val inputBytes = byteArrayOf(1, 2, 3, 4, 5)
            val expectedBase64 = "AQIDBAU="
            every { Base64.encodeToString(inputBytes, Base64.NO_WRAP) } returns expectedBase64

            // Act
            val result = encoder.encodeToBase64(inputBytes)

            // Assert
            assertEquals(expectedBase64, result)
        }
    }

    @Nested
    @DisplayName("bitmapToScreenshotData")
    inner class BitmapToScreenshotDataTests {
        @Test
        @DisplayName("returns ScreenshotData with correct format, dimensions, and non-empty data")
        fun `returns complete ScreenshotData`() {
            // Arrange
            val width = 1080
            val height = 2400
            val quality = 80
            val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val base64Data = "/9j/"

            val bitmap =
                mockk<Bitmap>(relaxed = true) {
                    every { getWidth() } returns width
                    every { getHeight() } returns height
                    every {
                        compress(Bitmap.CompressFormat.JPEG, quality, any<OutputStream>())
                    } answers {
                        val stream = thirdArg<OutputStream>()
                        stream.write(jpegBytes)
                        true
                    }
                }
            every { Base64.encodeToString(any<ByteArray>(), Base64.NO_WRAP) } returns base64Data

            // Act
            val result = encoder.bitmapToScreenshotData(bitmap, quality)

            // Assert
            assertEquals(ScreenshotData.FORMAT_JPEG, result.format)
            assertEquals(base64Data, result.data)
            assertEquals(width, result.width)
            assertEquals(height, result.height)
        }
    }
}
