package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollReach
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The list most scripted screens scroll: its visible extent E is 1200. */
internal val LIST = BoundsData(0, 200, 720, 1400)

/** The root of a scripted screen: [FAKE_SCREEN] holding [children]. */
internal fun screen(vararg children: FakeNode): FakeNode =
    FakeNode("root", BoundsData(0, 0, FAKE_SCREEN.width, FAKE_SCREEN.height), children.toList())

internal fun list(
    reach: ScrollReach,
    vararg rows: FakeNode,
    bounds: BoundsData = LIST,
    name: String = "list",
): FakeNode = FakeNode(name, bounds, rows.toList(), reach = reach)

/** A row of [LIST] whose top is at [rawTop] before clipping: a view of its full size, or Compose (0x0) when not. */
internal fun row(
    name: String,
    rawTop: Int,
    height: Int = 200,
    view: Boolean = true,
    text: String = name,
    live: String = name,
    children: List<FakeNode> = emptyList(),
): FakeNode =
    FakeNode(
        name,
        clip(BoundsData(0, rawTop, 720, rawTop + height), LIST),
        children = children,
        fullSize = if (view) 720 to height else null,
        text = text,
        live = live,
    )

/** The lines of the reply, scrolled note included. */
internal fun NodeScreenshots.lines(): List<String> = text.split("\n")

internal const val PARTIAL_TEXT = "partial: cut by the edge of its scrolling container"
internal const val MAY_BE_PARTIAL_TEXT = "may be partial: it touches the edge of its scrolling container"

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NodeScreenshotSession")
class NodeScreenshotSessionTest {
    /** A whole row A, a cut view B, a cut Compose node C and an off-screen view D. */
    private fun mixedScreen(): FakeNode =
        screen(
            list(
                reach(down = true),
                FakeNode("A", BoundsData(0, 200, 720, 400), fullSize = 720 to 200),
                FakeNode("B", BoundsData(0, 1300, 360, 1400), fullSize = 360 to 200),
                FakeNode("C", BoundsData(360, 1300, 720, 1400)),
                FakeNode("D", BoundsData(0, 1400, 720, 1400), fullSize = 720 to 200),
            ),
        )

    @Test
    fun `no scroll gives every line kind from a single capture in request order`() =
        runTest {
            val device = FakeDevice(mixedScreen())

            val shots = device.session().run(listOf("D", "A", "X", "C", "B"), allowScroll = false)

            assertEquals(
                listOf(
                    "D: not visible: off screen",
                    "A: image 1",
                    "X: not found",
                    "C: image 2, $MAY_BE_PARTIAL_TEXT",
                    "B: image 3, $PARTIAL_TEXT",
                ),
                shots.lines(),
            )
            assertEquals(listOf("c1/A", "c1/C", "c1/B"), shots.images)
            assertEquals(1, shots.captures)
            assertEquals(1, device.reads)
            assertTrue(device.drags.isEmpty() && device.shows.isEmpty())
            assertFalse(shots.scrolled)
        }

    @Test
    fun `no scroll takes no screenshot when no requested node is on screen`() =
        runTest {
            val device = FakeDevice(mixedScreen())

            val shots = device.session().run(listOf("D"), allowScroll = false)

            assertEquals(listOf("D: not visible: off screen"), shots.lines())
            assertEquals(0, shots.captures)
            assertTrue(device.bitmaps.isEmpty())
        }

    @Test
    fun `an empty crop is not captured and uses no image number`() =
        runTest {
            val device = FakeDevice(mixedScreen())
            device.emptyCrops.add("A")

            val shots = device.session().run(listOf("A", "B"), allowScroll = false)

            assertEquals(
                listOf("A: not captured: it could not be shown on screen", "B: image 1, $PARTIAL_TEXT"),
                shots.lines(),
            )
            assertEquals(listOf("c1/B"), shots.images)
        }

    @Test
    fun `images are numbered in request order when captured in another order`() =
        runTest {
            // B is whole in the first read; A, a tall Compose node cut at the bottom, comes later in 2 parts.
            val device =
                FakeDevice(
                    screen(list(reach(down = true), row("B", 200), row("A", 1000, height = 1500, view = false))),
                    screen(list(reach(up = true, down = true), row("A", 200, height = 1500, view = false))),
                    screen(list(reach(up = true), row("A", -700, height = 1500, view = false))),
                )

            val shots = device.session().run(listOf("A", "B"), allowScroll = true)

            assertEquals(
                listOf(SCROLLED_NOTE, "A: images 1-2: 2 overlapping parts, in order", "B: image 3"),
                shots.lines(),
            )
            assertEquals(listOf("c2/A", "c3/A", "c1/B"), shots.images)
        }

    @Test
    fun `masking uses the flagged bounds of each capture's read`() =
        runTest {
            val device =
                FakeDevice(
                    screen(list(reach(down = true), row("R0", 200), row("R9", 2000))),
                    screen(list(reach(up = true, down = true), row("R9", 1200))),
                )
            val first = listOf(BoundsData(1, 2, 3, 4))
            val second = listOf(BoundsData(5, 6, 7, 8))
            device.flagged[0] = first
            device.flagged[1] = second

            device.session().run(listOf("R0", "R9"), allowScroll = true)

            assertEquals(listOf(first, second), device.maskedWith)
        }

    @Test
    fun `a HARDWARE capture is copied once for all its crops and every bitmap is recycled`() =
        runTest {
            val device = FakeDevice(mixedScreen())
            device.bitmapConfig = Bitmap.Config.HARDWARE

            device.session().run(listOf("A", "B", "C"), allowScroll = false)

            assertEquals(1, device.copies.size)
            val copy = device.copies.single()
            verify(exactly = 3) { device.nodeImageCropper.crop(copy, any(), 720, 1600) }
            verify(exactly = 1) { copy.recycle() }
            verify(exactly = 1) { device.bitmaps.single().recycle() }
        }

    @Test
    fun `a privacy failure propagates`() =
        runTest {
            val device = FakeDevice(mixedScreen())
            coEvery { device.privacyToolGate.tree(any()) } throws McpToolException.PrivacyModeUnavailable("model gone")

            val error =
                assertThrows<McpToolException.PrivacyModeUnavailable> { device.session().run(listOf("A"), false) }

            assertEquals("model gone", error.message)
        }

    @Test
    fun `a capture failure is reported as such`() =
        runTest {
            val device = FakeDevice(mixedScreen())
            coEvery { device.screenCaptureProvider.captureScreenshotBitmap(null, null) } returns
                Result.failure(RuntimeException("boom"))

            val error = assertThrows<McpToolException.ActionFailed> { device.session().run(listOf("A"), false) }

            assertEquals("Screenshot capture failed", error.message)
        }

    @Test
    fun `an encode failure is reported as such and the bitmap is recycled`() =
        runTest {
            val device = FakeDevice(mixedScreen())
            every { device.nodeImageCropper.crop(any(), any(), any(), any()) } throws RuntimeException("boom")

            val error = assertThrows<McpToolException.ActionFailed> { device.session().run(listOf("A"), false) }

            assertEquals("Screenshot encoding failed", error.message)
            verify(exactly = 1) { device.bitmaps.single().recycle() }
        }

    /** A web paragraph cut at both ends whatever scrolls: its show moves nothing. */
    private fun endlessWebParagraph(): FakeDevice =
        FakeDevice(
            screen(
                FakeNode(
                    "web",
                    LIST,
                    listOf(FakeNode("W", LIST, webRole = "paragraph")),
                    reach = reach(up = true, down = true),
                ),
            ),
        )

    @Test
    fun `an error after a drag carries the scrolled note and keeps its class`() =
        runTest {
            val device = endlessWebParagraph()
            val unavailable = "Accessibility service not enabled or not ready"
            every { device.accessibilityServiceProvider.isReady() } answers { device.drags.isEmpty() }

            val error =
                assertThrows<McpToolException.PermissionDenied> { device.session().run(listOf("W"), true) }

            assertTrue(error.message!!.startsWith(unavailable))
            assertTrue(error.message!!.endsWith(" $SCROLLED_NOTE"))
            assertTrue(error.cause is McpToolException.PermissionDenied)
        }

    @Test
    fun `an error before any scroll carries no scrolled note`() =
        runTest {
            val device = endlessWebParagraph()
            every { device.accessibilityServiceProvider.isReady() } returns false

            val error =
                assertThrows<McpToolException.PermissionDenied> { device.session().run(listOf("W"), true) }

            assertFalse(error.message!!.contains(SCROLLED_NOTE))
        }

    @Test
    fun `the scrolled note follows a show that moved its node at once`() =
        runTest {
            val device =
                FakeDevice(
                    screen(list(reach(down = true), row("R9", 1300))),
                    screen(list(reach(up = true, down = true), row("R9", 1200))),
                )

            val shots = device.session().run(listOf("R9"), allowScroll = true)

            assertEquals(listOf(SCROLLED_NOTE, "R9: image 1"), shots.lines())
            assertTrue(device.drags.isEmpty())
        }

    @Test
    fun `the scrolled note follows a drag that failed once dispatched`() =
        runTest {
            val device = endlessWebParagraph()
            device.dragResults.add(Result.failure(RuntimeException("Gesture cancelled")))

            val shots = device.session().run(listOf("W"), allowScroll = true)

            assertEquals(SCROLLED_NOTE, shots.lines().first())
        }

    @Test
    fun `no scrolled note follows drags refused before dispatch`() =
        runTest {
            for (refusal in listOf(IllegalArgumentException("too short"), IllegalStateException("not available"))) {
                val device = endlessWebParagraph()
                repeat(MAX_DRAGS_OF_A_WEB_PARAGRAPH) { device.dragResults.add(Result.failure(refusal)) }

                val shots = device.session().run(listOf("W"), allowScroll = true)

                assertFalse(shots.scrolled, "$refusal")
                assertEquals("W: image 1; its start was not reached; the node continues", shots.lines().single())
            }
        }

    @Test
    fun `no scrolled note follows a drag with no segment`() =
        runTest {
            // A container inside the screen's top margin leaves no room for a drag.
            val strip = BoundsData(0, 0, 720, 150)
            val device =
                FakeDevice(
                    screen(
                        FakeNode(
                            "web",
                            strip,
                            listOf(FakeNode("W", strip, webRole = "paragraph")),
                            reach = reach(up = true, down = true),
                        ),
                    ),
                )

            val shots = device.session().run(listOf("W"), allowScroll = true)

            assertFalse(shots.scrolled)
            assertTrue(device.drags.isEmpty())
            assertEquals("W: image 1; its start was not reached; the node continues", shots.lines().single())
        }

    @Test
    fun `a cancellation during a show's settle stops the call`() =
        runTest {
            val device =
                FakeDevice(
                    screen(list(reach(down = true), row("R9", 2000))),
                    screen(list(reach(up = true, down = true), row("R9", 1200))),
                )

            val job = launch { device.session().run(listOf("R9"), allowScroll = true) }
            advanceTimeBy(SHOW_SETTLE_PARTWAY_MS)
            job.cancel()
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertEquals(1, device.reads)
            assertTrue(device.drags.isEmpty())
            assertTrue(device.bitmaps.isEmpty())
        }

    @Test
    fun `a cancellation during a drag's settle stops the call`() =
        runTest {
            val device = endlessWebParagraph()

            val job = launch { device.session().run(listOf("W"), allowScroll = true) }
            // The show settles in 3 polls of 50 ms, then the first back drag waits 500 ms.
            advanceTimeBy(DRAG_SETTLE_PARTWAY_MS)
            job.cancel()
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertEquals(2, device.reads)
            assertEquals(1, device.drags.size)
            assertTrue(device.bitmaps.isEmpty())
        }

    companion object {
        /** 3 back drags and 2 forward drags. */
        private const val MAX_DRAGS_OF_A_WEB_PARAGRAPH = 5
        private const val SHOW_SETTLE_PARTWAY_MS = 75L
        private const val DRAG_SETTLE_PARTWAY_MS = 400L
    }
}
