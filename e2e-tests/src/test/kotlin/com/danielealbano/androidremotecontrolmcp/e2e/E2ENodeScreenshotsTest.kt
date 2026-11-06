package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * E2E tests of `get_node_screenshots` on the compose test app's NodeScreenshotActivity (40 view rows and two tall
 * blocks in a ScrollView: the rows below the fold are off-screen nodes) and NodeScreenshotComposeActivity (a block
 * cut at the bottom of a scrolling Compose column; Compose drops nodes scrolled fully out of view, so the off-screen
 * cases use views).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2ENodeScreenshotsTest {
    private val mcpClient = SharedAndroidContainer.mcpClient

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    private fun textOf(result: CallToolResult): String = (result.content.firstOrNull() as? TextContent)?.text.orEmpty()

    private fun linesOf(result: CallToolResult): List<String> = stripUntrustedWarning(textOf(result)).split("\n")

    private fun imagesOf(result: CallToolResult): List<ImageContent> = result.content.drop(1).map { it as ImageContent }

    private fun JsonObject.nodeId(): String = getValue("node_id").jsonPrimitive.content

    private fun JsonObject.isVisible(): Boolean = getValue("visible").jsonPrimitive.boolean

    private fun JsonObject.edge(name: String): Int = getValue("bounds").jsonObject.getValue(name).jsonPrimitive.int

    /** The nodes whose content description is [desc] (or contains it, when not [exact]). */
    private suspend fun findByDesc(
        desc: String,
        exact: Boolean = true,
    ): List<JsonObject> {
        val result =
            mcpClient.callTool(
                "${TOOL_PREFIX}find_nodes",
                mapOf("by" to "content_desc", "value" to desc, "exact_match" to exact),
            )
        val nodes = Json.parseToJsonElement(stripUntrustedWarning(textOf(result))).jsonObject["nodes"]?.jsonArray
        return nodes?.map { it.jsonObject }.orEmpty()
    }

    /** The first node of each of [descs], or null while one of them is missing. */
    private suspend fun byDesc(vararg descs: String): Map<String, JsonObject>? {
        val nodes = descs.associateWith { findByDesc(it).firstOrNull() }
        return if (nodes.values.all { it != null }) nodes.mapValues { (_, node) -> checkNotNull(node) } else null
    }

    /**
     * The list, its first row and the row cut by its bottom edge: visible, with a height, and ending where the list
     * ends (the rows below the fold also have their bottom clamped there, with no height).
     */
    private suspend fun cutRowRead(): Map<String, JsonObject>? {
        val nodes = byDesc("shot_list", "shot_row_0") ?: return null
        val listBottom = nodes.getValue("shot_list").edge("bottom")
        val cut =
            findByDesc("shot_row_", exact = false).firstOrNull {
                it.isVisible() && it.edge("top") < it.edge("bottom") && it.edge("bottom") == listBottom
            }
        return cut?.let { nodes + ("cut" to it) }
    }

    /**
     * Launches with [launch], then returns what [read] finds once the ids of every node it returns are the same in
     * [STABLE_READS] reads in a row: node ids hash the bounds, and after a launch the content keeps moving for a while
     * (the loop of E2EScrollToNodeTest).
     */
    private suspend fun launchAndSettle(
        launch: () -> Unit,
        read: suspend () -> Map<String, JsonObject>?,
    ): Map<String, JsonObject> {
        launch()
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        var previous: Map<String, String>? = null
        var stableReads = 0
        var last: Map<String, JsonObject>? = null
        while (System.currentTimeMillis() < deadline) {
            last = read()
            val ids = last?.mapValues { (_, node) -> node.nodeId() }
            stableReads = if (ids != null && ids == previous) stableReads + 1 else if (ids != null) 1 else 0
            previous = ids
            if (last != null && stableReads == STABLE_READS) return last
            Thread.sleep(POLL_MS)
        }
        error("the screen did not settle within ${LAUNCH_TIMEOUT_MS}ms, last read: $last")
    }

    private suspend fun viewsSettled(vararg descs: String): Map<String, JsonObject> =
        launchAndSettle(E2ETestApps::launchNodeScreenshotTestApp) { byDesc(*descs) }

    private suspend fun composeSettled(): Map<String, JsonObject> =
        launchAndSettle(E2ETestApps::launchNodeScreenshotComposeTestApp) { byDesc("compose_row_0", "compose_tall") }

    /** The line of [id] in 2 or 3 parts, with no note: the group is the number of parts. */
    private fun twoOrThreeParts(id: String): Regex =
        Regex("^${Regex.escape(id)}: images 1-([23]): \\1 overlapping parts, in order$")

    private suspend fun shoot(
        ids: List<String>,
        allowScroll: Boolean,
    ): CallToolResult {
        val result =
            mcpClient.callTool(
                "${TOOL_PREFIX}get_node_screenshots",
                mapOf("node_ids" to ids, "allow_scroll" to allowScroll),
            )
        assertNotEquals(true, result.isError, textOf(result))
        assertTrue(textOf(result).startsWith("CAUTION:"), textOf(result))
        return result
    }

    @Test
    @Order(1)
    fun `a visible row comes as one JPEG of at most 700 px`() =
        runBlocking {
            val id = viewsSettled("shot_row_0").getValue("shot_row_0").nodeId()

            val result = shoot(listOf(id), allowScroll = false)

            assertEquals(listOf("$id: image 1"), linesOf(result))
            val image = imagesOf(result).single()
            assertEquals("image/jpeg", image.mimeType)
            val decoded = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.data)))
            assertNotNull(decoded, "the image must decode")
            assertTrue(maxOf(decoded.width, decoded.height) <= MAX_IMAGE_SIDE, "${decoded.width}x${decoded.height}")
        }

    @Test
    @Order(2)
    fun `an off-screen row gets no image`() =
        runBlocking {
            val id = viewsSettled("shot_row_30").getValue("shot_row_30").nodeId()

            val result = shoot(listOf(id), allowScroll = false)

            assertEquals(listOf("$id: not visible: off screen"), linesOf(result))
            assertTrue(imagesOf(result).isEmpty())
        }

    @Test
    @Order(3)
    fun `the row cut by the bottom of the list is captured as shown, marked partial, and nothing moves`() =
        runBlocking {
            val nodes = launchAndSettle(E2ETestApps::launchNodeScreenshotTestApp) { cutRowRead() }
            val cut = nodes.getValue("cut")

            val result = shoot(listOf(cut.nodeId()), allowScroll = false)

            assertEquals(listOf("${cut.nodeId()}: image 1, $PARTIAL"), linesOf(result))
            assertEquals(1, imagesOf(result).size)
            val firstRow = nodes.getValue("shot_row_0")
            assertEquals(firstRow.getValue("bounds"), byDesc("shot_row_0")?.getValue("shot_row_0")?.getValue("bounds"))
        }

    @Test
    @Order(4)
    fun `a far row and a visible row come as two images once the app scrolled`() =
        runBlocking {
            val nodes = viewsSettled("shot_row_0", "shot_row_30")
            val far = nodes.getValue("shot_row_30").nodeId()
            val visible = nodes.getValue("shot_row_0").nodeId()

            val result = shoot(listOf(far, visible), allowScroll = true)

            assertEquals(listOf(SCROLLED_NOTE, "$far: image 1", "$visible: image 2"), linesOf(result))
            assertEquals(2, imagesOf(result).size)
        }

    @Test
    @Order(5)
    fun `a block 1,6 screens tall comes in 2 or 3 parts`() =
        runBlocking {
            val id = viewsSettled("tall_two").getValue("tall_two").nodeId()

            val result = shoot(listOf(id), allowScroll = true)

            val line = linesOf(result).last()
            val parts = twoOrThreeParts(id).find(line)
            assertNotNull(parts, line)
            assertEquals(parts!!.groupValues[1].toInt(), imagesOf(result).size)
        }

    @Test
    @Order(6)
    fun `a block 4 screens tall comes in 3 parts and continues`() =
        runBlocking {
            val id = viewsSettled("tall_long").getValue("tall_long").nodeId()

            val result = shoot(listOf(id), allowScroll = true)

            assertEquals("$id: images 1-3: 3 overlapping parts, in order; the node continues", linesOf(result).last())
            assertEquals(3, imagesOf(result).size)
        }

    @Test
    @Order(7)
    fun `rows off screen together are aligned and captured whole`() =
        runBlocking {
            val nodes = viewsSettled("shot_row_20", "shot_row_21", "shot_row_22")
            val ids = listOf("shot_row_20", "shot_row_21", "shot_row_22").map { nodes.getValue(it).nodeId() }

            val result = shoot(ids, allowScroll = true)

            assertEquals(
                listOf(SCROLLED_NOTE) + ids.mapIndexed { index, id -> "$id: image ${index + 1}" },
                linesOf(result),
            )
        }

    @Test
    @Order(8)
    fun `a Compose block cut at the bottom may be partial without scrolling`() =
        runBlocking {
            val id = composeSettled().getValue("compose_tall").nodeId()

            val result = shoot(listOf(id), allowScroll = false)

            assertEquals(listOf("$id: image 1, $MAY_BE_PARTIAL"), linesOf(result))
        }

    @Test
    @Order(9)
    fun `a Compose block cut at the bottom comes in 2 or 3 parts with scrolling`() =
        runBlocking {
            val id = composeSettled().getValue("compose_tall").nodeId()

            val result = shoot(listOf(id), allowScroll = true)

            val line = linesOf(result).last()
            assertNotNull(twoOrThreeParts(id).find(line), line)
        }

    @Test
    @Order(10)
    fun `more than 100 node ids or a duplicate are errors`() =
        runBlocking {
            val tooMany =
                mcpClient.callTool("${TOOL_PREFIX}get_node_screenshots", mapOf("node_ids" to List(101) { "n$it" }))
            val duplicate =
                mcpClient.callTool("${TOOL_PREFIX}get_node_screenshots", mapOf("node_ids" to listOf("a", "b", "a")))

            assertEquals(true, tooMany.isError, textOf(tooMany))
            assertTrue(textOf(tooMany).contains("at most 100"), textOf(tooMany))
            assertEquals(true, duplicate.isError, textOf(duplicate))
            assertTrue(textOf(duplicate).contains("duplicates: 'a'"), textOf(duplicate))
        }

    private companion object {
        const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val POLL_MS = 500L

        /** Reads in a row, each at least POLL_MS after the previous one, with the same ids. */
        const val STABLE_READS = 3
        const val MAX_IMAGE_SIDE = 700
        const val PARTIAL = "partial: cut by the edge of its scrolling container"
        const val MAY_BE_PARTIAL = "may be partial: it touches the edge of its scrolling container"
        const val SCROLLED_NOTE =
            "The app was scrolled to capture the nodes: node ids have changed, read the screen again before using " +
                "any node id."
    }
}
