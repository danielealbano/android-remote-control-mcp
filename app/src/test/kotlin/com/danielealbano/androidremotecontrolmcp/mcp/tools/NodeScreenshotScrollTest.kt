package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.DragSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollReach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** The `allow_scroll` flow of [NodeScreenshotSession], on scripted screens ([FakeDevice]). */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NodeScreenshotSession with allow_scroll")
class NodeScreenshotScrollTest {
    /** View rows r0..r12 of [LIST], 200 px each, scrolled by [scrollY]. */
    private fun viewRows(
        scrollY: Int,
        reach: ScrollReach,
    ): FakeNode = screen(list(reach, *Array(ROWS) { k -> row("r$k", LIST.top + ROW * k - scrollY) }))

    private suspend fun FakeDevice.shoot(vararg ids: String): NodeScreenshots = session().run(ids.toList(), true)

    private val middle = reach(up = true, down = true)

    @Nested
    @DisplayName("Rounds")
    inner class Rounds {
        @Test
        fun `the targets whole in the first read are captured together first`() =
            runTest {
                val device = FakeDevice(viewRows(0, reach(down = true)), viewRows(800, middle))

                val shots = device.shoot("r9", "r0", "r1")

                assertEquals(listOf(SCROLLED_NOTE, "r9: image 1", "r0: image 2", "r1: image 3"), shots.lines())
                assertEquals(listOf("c2/r9", "c1/r0", "c1/r1"), shots.images)
                assertEquals(listOf(1, 2), device.capturedAtRead)
            }

        @Test
        fun `a round whose alignment brings two more targets in takes them in one screenshot`() =
            runTest {
                val device = FakeDevice(viewRows(0, reach(down = true)), viewRows(400, middle), viewRows(1325, middle))

                val shots = device.shoot("r7", "r8", "r9")

                assertEquals(listOf(SCROLLED_NOTE, "r7: image 1", "r8: image 2", "r9: image 3"), shots.lines())
                assertEquals(1, shots.captures)
                assertEquals(listOf(DragSegment(360f, 1325f, 360f, 400f)), device.drags)
            }

        @Test
        fun `the alignment is skipped when the head is already near the top`() =
            runTest {
                val device = FakeDevice(viewRows(100, middle), viewRows(0, reach(down = true)), viewRows(1400, middle))

                val shots = device.shoot("r0", "r12")

                assertEquals(listOf(SCROLLED_NOTE, "r0: image 1", "r12: image 2"), shots.lines())
                assertTrue(device.drags.isEmpty())
            }

        @Test
        fun `the alignment is skipped when nothing else is pending`() =
            runTest {
                val device = FakeDevice(viewRows(0, reach(down = true)), viewRows(800, middle))

                val shots = device.shoot("r9")

                assertEquals(listOf(SCROLLED_NOTE, "r9: image 1"), shots.lines())
                assertTrue(device.drags.isEmpty())
            }

        @Test
        fun `the alignment is skipped when its drag is refused`() =
            runTest {
                val device = FakeDevice(viewRows(0, reach(down = true)), viewRows(400, middle), viewRows(1325, middle))
                device.dragResults.add(Result.failure(IllegalArgumentException("too short")))

                val shots = device.shoot("r7", "r8")

                assertEquals(listOf(SCROLLED_NOTE, "r7: image 1", "r8: image 2"), shots.lines())
                // r7 from the read after its show, r8 from the read after its own show.
                assertEquals(listOf(2, 3), device.capturedAtRead)
            }

        @Test
        fun `a dispatched drag that fails is followed by its settle and a read before the capture`() =
            runTest {
                val device = FakeDevice(viewRows(0, reach(down = true)), viewRows(400, middle), viewRows(1325, middle))
                device.dragResults.add(Result.failure(RuntimeException("Gesture cancelled")))

                val shots = device.shoot("r7", "r8", "r9")

                assertEquals(listOf(SCROLLED_NOTE, "r7: image 1", "r8: image 2", "r9: image 3"), shots.lines())
                assertEquals(listOf(3), device.capturedAtRead)
                // The show's settle (3 polls), then the failed drag's settle.
                assertEquals(3 * ShotScroller.SHOW_POLL_MS + ShotScroller.DRAG_SETTLE_MS, testScheduler.currentTime)
            }

        @Test
        fun `a head no longer whole after the alignment is shown again`() =
            runTest {
                val device =
                    FakeDevice(
                        viewRows(0, reach(down = true)),
                        viewRows(400, middle),
                        viewRows(1500, middle),
                        viewRows(1400, middle),
                    )

                val shots = device.shoot("r7", "r8")

                assertEquals(listOf(SCROLLED_NOTE, "r7: image 1", "r8: image 2"), shots.lines())
                assertEquals(listOf("r7", "r7"), device.shows)
                assertEquals(1, shots.captures)
            }

        @Test
        fun `a head off screen after being shown again is not captured`() =
            runTest {
                val device =
                    FakeDevice(
                        viewRows(0, reach(down = true)),
                        viewRows(400, middle),
                        viewRows(1500, middle),
                        viewRows(2000, middle),
                        viewRows(1600, middle),
                    )

                val shots = device.shoot("r7", "r8")

                assertEquals(
                    listOf(SCROLLED_NOTE, "r7: not captured: it could not be shown on screen", "r8: image 1"),
                    shots.lines(),
                )
            }

        @Test
        fun `a head left cut after being shown again is captured with its cut line`() =
            runTest {
                val device =
                    FakeDevice(
                        viewRows(0, reach(down = true)),
                        viewRows(400, middle),
                        viewRows(1500, middle),
                        viewRows(1500, middle),
                    )

                val shots = device.shoot("r7", "r8")

                assertEquals(listOf(SCROLLED_NOTE, "r7: image 1, $PARTIAL_TEXT", "r8: image 2"), shots.lines())
            }

        @Test
        fun `a head still off screen after its show is not captured`() =
            runTest {
                val device = FakeDevice(viewRows(0, reach(down = true)))

                val shots = device.shoot("r9")

                assertEquals(listOf("r9: not captured: it could not be shown on screen"), shots.lines())
            }

        @Test
        fun `a changed head is reported changed`() =
            runTest {
                val device =
                    FakeDevice(
                        screen(list(middle, row("H1", 100), row("H2", 1500))),
                        screen(list(middle, row("H1", 200), row("H2", 1500, text = "other"))),
                    )
                // The seek's drag is refused, so nothing else is read.
                device.dragResults.add(Result.failure(IllegalArgumentException("too short")))

                val shots = device.shoot("H1", "H2")

                assertEquals(
                    listOf(SCROLLED_NOTE, "H1: image 1", "H2: not captured: it changed while the app scrolled"),
                    shots.lines(),
                )
            }
    }

    @Nested
    @DisplayName("Alignment container")
    inner class AlignmentContainer {
        @Test
        fun `a carousel item in a list aligns on the list`() =
            runTest {
                fun carousel(rawTop: Int) =
                    FakeNode(
                        "K",
                        clip(BoundsData(0, rawTop, 720, rawTop + ROW), LIST),
                        listOf(
                            FakeNode("K1", clip(BoundsData(0, rawTop, 300, rawTop + ROW), LIST), fullSize = 300 to ROW),
                        ),
                        reach = reach(right = true),
                    )
                val device =
                    FakeDevice(
                        screen(list(reach(down = true), carousel(1600), row("Z", 2200))),
                        screen(list(middle, carousel(1200), row("Z", 1800))),
                        screen(list(middle, carousel(275), row("Z", 875))),
                    )

                val shots = device.shoot("K1", "Z")

                assertEquals(listOf(SCROLLED_NOTE, "K1: image 1", "Z: image 2"), shots.lines())
                assertEquals(listOf(DragSegment(150f, 1325f, 150f, 400f)), device.drags)
            }

        @Test
        fun `a head whose only scrolling ancestor reaches right aligns horizontally`() =
            runTest {
                val strip = BoundsData(0, 600, 720, 800)

                fun carousel(
                    scrollX: Int,
                    reach: ScrollReach,
                ) = FakeNode(
                    "K",
                    strip,
                    List(CAROUSEL_ITEMS) { i ->
                        val left = CAROUSEL_ITEM * i - scrollX
                        val bounds = clip(BoundsData(left, 600, left + CAROUSEL_ITEM, 800), strip)
                        FakeNode("k$i", bounds, fullSize = CAROUSEL_ITEM to ROW)
                    },
                    reach = reach,
                )
                val sides = reach(left = true, right = true)
                val device =
                    FakeDevice(
                        screen(carousel(0, reach(right = true))),
                        screen(carousel(480, sides)),
                        screen(carousel(855, sides)),
                    )

                val shots = device.shoot("k3", "k4")

                assertEquals(listOf(SCROLLED_NOTE, "k3: image 1", "k4: image 2"), shots.lines())
                assertEquals(listOf(DragSegment(630f, 700f, 255f, 700f)), device.drags)
            }

        @Test
        fun `no scrolling ancestor reaching down or right means no alignment`() =
            runTest {
                val side = BoundsData(0, 1400, 720, 1600)

                fun sideList(z: BoundsData) =
                    FakeNode("side", side, listOf(FakeNode("Z", z, fullSize = 720 to ROW)), reach = reach(down = true))
                val device =
                    FakeDevice(
                        screen(list(reach(down = true), row("H", 1300)), sideList(BoundsData(0, 1600, 720, 1600))),
                        screen(list(reach(up = true), row("H", 1200)), sideList(BoundsData(0, 1600, 720, 1600))),
                        screen(list(reach(up = true), row("H", 1200)), sideList(side)),
                    )

                val shots = device.shoot("H", "Z")

                assertEquals(listOf(SCROLLED_NOTE, "H: image 1", "Z: image 2"), shots.lines())
                assertTrue(device.drags.isEmpty())
            }
    }

    @Nested
    @DisplayName("Seek")
    inner class Seek {
        /** H1 cut at the top and H2 cut at the bottom of a Compose list; H1's show drops H2 from the tree. */
        private fun droppedBelow(vararg after: FakeNode): FakeDevice =
            FakeDevice(
                screen(list(middle, row("H1", 100, view = false), row("H2", 1300, view = false))),
                screen(list(middle, row("H1", 200, view = false))),
                *after,
            )

        @Test
        fun `a carousel dropped with its row is sought on the outer list and found again as a new node`() =
            runTest {
                // H1, a tall Compose node, holds carousel C whose item H2 is cut by C's right edge. H1's show moves
                // the content up 200 px, its part 2 drags it up 900 px: H2, last seen below the list's centre, leaves
                // by the top with C. The forward drag says to seek backward, where its last place alone would say
                // forward.
                fun h1(
                    top: Int,
                    vararg children: FakeNode,
                ) = FakeNode("H1", clip(BoundsData(0, top, 720, top + 2000), LIST), children.toList())

                fun carousel(
                    top: Int,
                    live: String,
                    h2: FakeNode,
                    reach: ScrollReach = reach(right = true),
                ) = FakeNode("C", BoundsData(0, top, 720, top + ROW), listOf(h2), reach = reach, live = live)

                fun h2(
                    top: Int,
                    left: Int = 100,
                    live: String = "H2",
                ) = FakeNode("H2", BoundsData(left, top, 720, top + ROW), live = live)
                val device =
                    FakeDevice(
                        screen(list(middle, h1(400, carousel(1100, "C", h2(1100))))),
                        screen(list(middle, h1(200, carousel(900, "C", h2(900))))),
                        screen(list(middle, h1(-700))),
                        screen(list(middle, h1(200, carousel(900, "C2", h2(900, live = "H2b"))))),
                        screen(
                            list(
                                middle,
                                h1(200, carousel(900, "C2", h2(900, 20, "H2b"), reach(left = true, right = true))),
                            ),
                        ),
                    )

                val shots = device.shoot("H1", "H2")

                assertEquals(
                    listOf(SCROLLED_NOTE, "H1: images 1-2: 2 overlapping parts, in order", "H2: image 3"),
                    shots.lines(),
                )
                assertEquals(
                    listOf(DragSegment(360f, 1250f, 360f, 350f), DragSegment(360f, 350f, 360f, 1250f)),
                    device.drags,
                )
                assertEquals(listOf("H1", "H2b"), device.shows)
            }

        @Test
        fun `a pending target dropped from the read of a capture is captured later as a head`() =
            runTest {
                val device =
                    droppedBelow(
                        screen(list(middle, row("H2", 500, view = false, live = "H2b"))),
                        screen(list(middle, row("H2", 500, view = false, live = "H2b"))),
                    )

                val shots = device.shoot("H1", "H2")

                assertEquals(listOf("c1/H1", "c2/H2"), shots.images)
                // H1 from the read after its show, where H2 was gone; H2 from the read after its own show.
                assertEquals(listOf(2, 4), device.capturedAtRead)
            }

        @Test
        fun `with no movement since it was last seen a head is sought from where it was`() =
            runTest {
                val device =
                    droppedBelow(
                        screen(list(middle, row("H2", 500, view = false, live = "H2b"))),
                        screen(list(middle, row("H2", 500, view = false, live = "H2b"))),
                    )

                val shots = device.shoot("H1", "H2")

                assertEquals(listOf(SCROLLED_NOTE, "H1: image 1", "H2: image 2"), shots.lines())
                assertEquals(listOf(DragSegment(360f, 1250f, 360f, 350f)), device.drags)
            }

        @Test
        fun `an ambiguous match by content is not taken`() =
            runTest {
                val twins =
                    screen(
                        list(
                            middle,
                            row("H2", 500, view = false, live = "H2b"),
                            row("H2x", 700, view = false, text = "H2", live = "H2c"),
                        ),
                    )
                val device = droppedBelow(twins, twins)

                val shots = device.shoot("H1", "H2")

                assertEquals(
                    listOf(SCROLLED_NOTE, "H1: image 1", "H2: not captured: it disappeared while the app scrolled"),
                    shots.lines(),
                )
                assertEquals(2, device.drags.size)
            }

        @Test
        fun `a head none of whose last-seen containers is left disappears with no drag`() =
            runTest {
                fun box(
                    name: String,
                    bounds: BoundsData,
                    child: FakeNode,
                ) = FakeNode(name, bounds, listOf(child), reach = middle)
                val top = BoundsData(0, 200, 720, 700)
                val bottom = BoundsData(0, 800, 720, 1400)
                val device =
                    FakeDevice(
                        screen(
                            box("top", top, FakeNode("H1", BoundsData(0, 200, 720, 300))),
                            box("bottom", bottom, FakeNode("H2", BoundsData(0, 1300, 720, 1400))),
                        ),
                        screen(box("top", top, FakeNode("H1", BoundsData(0, 200, 720, 400)))),
                    )

                val shots = device.shoot("H1", "H2")

                assertEquals(
                    listOf(SCROLLED_NOTE, "H1: image 1", "H2: not captured: it disappeared while the app scrolled"),
                    shots.lines(),
                )
                assertTrue(device.drags.isEmpty())
            }

        @Test
        fun `a seek whose drag fails ends with the head disappeared`() =
            runTest {
                val device = droppedBelow(screen(list(middle, row("H1", 200, view = false))))
                device.dragResults.add(Result.failure(RuntimeException("Gesture cancelled")))

                val shots = device.shoot("H1", "H2")

                assertEquals(
                    listOf(SCROLLED_NOTE, "H1: image 1", "H2: not captured: it disappeared while the app scrolled"),
                    shots.lines(),
                )
                assertEquals(1, device.drags.size)
            }

        @Test
        fun `a seek drags its container at its bounds in the current read`() =
            runTest {
                // H1's show also expands a toolbar: the list now starts at 600.
                val shrunk = BoundsData(0, 600, 720, 1400)
                val device =
                    FakeDevice(
                        screen(list(middle, row("H1", 100, view = false), row("H2", 1300, view = false))),
                        screen(list(middle, FakeNode("H1", BoundsData(0, 600, 720, 800)), bounds = shrunk)),
                        screen(
                            list(middle, FakeNode("H2", BoundsData(0, 900, 720, 1100), live = "H2b"), bounds = shrunk),
                        ),
                    )

                val shots = device.shoot("H1", "H2")

                assertEquals(listOf(SCROLLED_NOTE, "H1: image 1", "H2: image 2"), shots.lines())
                assertEquals(listOf(DragSegment(360f, 1300f, 360f, 700f)), device.drags)
            }
    }

    @Nested
    @DisplayName("Seek direction")
    inner class SeekDirection {
        @Test
        fun `a forward movement since the target was seen means seeking backward, wherever it was`() {
            assertEquals(false, seekForward(firstMovement = true, targetCentre = 1000f, containerCentre = 800f))
        }

        @Test
        fun `a backward movement since the target was seen means seeking forward, wherever it was`() {
            assertEquals(true, seekForward(firstMovement = false, targetCentre = 300f, containerCentre = 800f))
        }

        @Test
        fun `with no movement a target before the container's centre is sought backward`() {
            assertEquals(false, seekForward(firstMovement = null, targetCentre = 300f, containerCentre = 800f))
        }

        @Test
        fun `with no movement a target past the container's centre is sought forward`() {
            assertEquals(true, seekForward(firstMovement = null, targetCentre = 1000f, containerCentre = 800f))
        }
    }

    @Nested
    @DisplayName("Parts")
    inner class Parts {
        /** A Compose node of [height] cut at the bottom of [LIST], shown from below to the top of the list. */
        private fun tallNode(
            height: Int,
            vararg afterShow: FakeNode,
        ): FakeDevice =
            FakeDevice(
                screen(list(reach(down = true), row("A", 1000, height, view = false))),
                screen(list(middle, row("A", LIST.top, height, view = false))),
                *afterShow,
            )

        private fun tallNodeAt(
            rawTop: Int,
            height: Int,
            text: String = "A",
        ) = screen(list(middle, row("A", rawTop, height, view = false, text = text)))

        @Test
        fun `a tall node shown from below comes in two parts from its top with no back drag`() =
            runTest {
                val device = tallNode(1500, tallNodeAt(-700, 1500))

                val shots = device.shoot("A")

                assertEquals(listOf(SCROLLED_NOTE, "A: images 1-2: 2 overlapping parts, in order"), shots.lines())
                assertEquals(listOf(DragSegment(360f, 1250f, 360f, 350f)), device.drags)
                assertEquals(listOf("A"), device.shows)
            }

        @Test
        fun `a node longer than three parts comes in three parts and continues`() =
            runTest {
                val device = tallNode(3200, tallNodeAt(-700, 3200), tallNodeAt(-1600, 3200))

                val shots = device.shoot("A")

                assertEquals(
                    listOf(SCROLLED_NOTE, "A: images 1-3: 3 overlapping parts, in order; the node continues"),
                    shots.lines(),
                )
            }

        @Test
        fun `a node whose start cannot be reached says so`() =
            runTest {
                val device = endlessWebParagraph()

                val shots = device.shoot("W")

                assertEquals(
                    listOf(
                        SCROLLED_NOTE,
                        "W: images 1-3: 3 overlapping parts, in order; its start was not reached; the node continues",
                    ),
                    shots.lines(),
                )
                assertEquals(MAX_BACK_DRAGS + 2, device.drags.size)
            }

        @Test
        fun `a web node keeps its cut after its show and a back drag past its start is aligned before part 1`() =
            runTest {
                fun web(
                    w: BoundsData,
                    reach: ScrollReach,
                ) = screen(FakeNode("web", LIST, listOf(FakeNode("W", w, webRole = "paragraph")), reach = reach))
                val device =
                    FakeDevice(
                        web(BoundsData(0, 1000, 720, 1400), reach(down = true)),
                        web(LIST, middle),
                        web(BoundsData(0, 1100, 720, 1400), middle),
                        web(BoundsData(0, 275, 720, 1400), middle),
                        web(LIST, middle),
                    )

                val shots = device.shoot("W")

                assertEquals(
                    listOf(SCROLLED_NOTE, "W: images 1-3: 3 overlapping parts, in order; the node continues"),
                    shots.lines(),
                )
                // Its show moved its start up: a non-web node would not have been cut there, so no back drag.
                assertEquals(DragSegment(360f, 350f, 360f, 1250f), device.drags[0])
                assertEquals(DragSegment(360f, 1325f, 360f, 500f), device.drags[1])
                assertEquals(listOf("W"), device.shows)
            }

        @Test
        fun `a node still cut on the other axis carries the partial wording`() =
            runTest {
                fun pager(n: BoundsData) =
                    screen(
                        FakeNode(
                            "h",
                            BoundsData(0, 0, 720, 1600),
                            listOf(list(middle, FakeNode("N", n, fullSize = 1000 to 2000))),
                            reach = reach(right = true),
                        ),
                    )
                val device =
                    FakeDevice(
                        pager(BoundsData(0, 600, 720, 1400)),
                        pager(LIST),
                        pager(BoundsData(0, 200, 720, 1300)),
                    )

                val shots = device.shoot("N")

                assertEquals(
                    listOf(SCROLLED_NOTE, "N: images 1-2: 2 overlapping parts, in order, $PARTIAL_TEXT"),
                    shots.lines(),
                )
            }

        @Test
        fun `a pending target whole in a part's read is captured with that part`() =
            runTest {
                val device =
                    FakeDevice(
                        screen(list(reach(down = true), row("A", 1000, 1600), row("P", 2600))),
                        screen(list(middle, row("A", 200, 1600), row("P", 1800))),
                        screen(list(middle, row("A", -700, 1600), row("P", 900))),
                    )

                val shots = device.shoot("A", "P")

                assertEquals(
                    listOf(SCROLLED_NOTE, "A: images 1-2: 2 overlapping parts, in order", "P: image 3"),
                    shots.lines(),
                )
                assertEquals(listOf("c1/A", "c2/A", "c2/P"), shots.images)
            }

        @Test
        fun `a node that disappears after a part keeps its parts with the note`() =
            runTest {
                val device = tallNode(1500, screen(list(reach(up = true))))

                val shots = device.shoot("A")

                assertEquals(
                    listOf(SCROLLED_NOTE, "A: image 1; then it disappeared while the app scrolled"),
                    shots.lines(),
                )
            }

        @Test
        fun `a node that changes after a part keeps its parts with the note`() =
            runTest {
                val device = tallNode(1500, tallNodeAt(-700, 1500, text = "other"))

                val shots = device.shoot("A")

                assertEquals(listOf(SCROLLED_NOTE, "A: image 1; then it changed while the app scrolled"), shots.lines())
            }

        @Test
        fun `a failed drag in the back phase starts the parts from the read after it`() =
            runTest {
                val device = endlessWebParagraph()
                device.dragResults.add(Result.failure(RuntimeException("Gesture cancelled")))

                val shots = device.shoot("W")

                assertEquals(
                    "W: images 1-3: 3 overlapping parts, in order; its start was not reached; the node continues",
                    shots.lines()[1],
                )
                // The first read, the read after the show, then the read after the failed back drag.
                assertEquals(3, device.capturedAtRead.first())
                assertEquals(3, device.drags.size)
            }

        @Test
        fun `a failed drag in the parts phase ends the parts at the last part`() =
            runTest {
                val device = tallNode(3200, tallNodeAt(-700, 3200))
                device.dragResults.add(Result.failure(RuntimeException("Gesture cancelled")))

                val shots = device.shoot("A")

                assertEquals(listOf(SCROLLED_NOTE, "A: image 1; the node continues"), shots.lines())
                assertEquals(1, shots.captures)
            }
    }

    companion object {
        private const val ROWS = 13
        private const val ROW = 200
        private const val CAROUSEL_ITEMS = 6
        private const val CAROUSEL_ITEM = 300
        private const val MAX_BACK_DRAGS = 3
    }
}
