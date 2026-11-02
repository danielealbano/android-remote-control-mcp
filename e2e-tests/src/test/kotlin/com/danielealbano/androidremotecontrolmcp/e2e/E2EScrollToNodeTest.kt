package com.danielealbano.androidremotecontrolmcp.e2e

import com.danielealbano.androidremotecontrolmcp.e2e.AndroidContainerSetup.execAdb
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E tests of the scrolling tools on a long list (60 rows in a plain ScrollView, see the compose test app's
 * LongListActivity): the rows below the fold are off-screen nodes, and scrolling moves them, which changes the
 * node ids (they hash the bounds).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EScrollToNodeTest {
    private val mcpClient = SharedAndroidContainer.mcpClient

    /** The container's window and transition animation scales before this class turned them off. */
    private var savedAnimationScales: Map<String, String> = emptyMap()

    /**
     * Turns the container's window and transition animations off while this class runs: a launch transition
     * transforms the window, which moves the rows' bounds, and so their ids, back and forth, and reads some time apart
     * can miss such a change (see [launchListAndFindTarget]).
     */
    @BeforeAll
    fun turnOffWindowAnimations() {
        savedAnimationScales =
            ANIMATION_SCALES.associateWith { execAdb("shell", "settings", "get", "global", it).trim() }
        ANIMATION_SCALES.forEach { execAdb("shell", "settings", "put", "global", it, "0") }
    }

    /** Restores the scales [turnOffWindowAnimations] saved; one that was not set ("null") is deleted again. */
    @AfterAll
    fun restoreWindowAnimations() {
        savedAnimationScales.forEach { (name, value) ->
            if (value == "null") {
                execAdb("shell", "settings", "delete", "global", name)
            } else {
                execAdb("shell", "settings", "put", "global", name, value)
            }
        }
    }

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    private fun textOf(result: CallToolResult): String = (result.content.firstOrNull() as? TextContent)?.text.orEmpty()

    /** The node whose text is exactly [text], or null when there is none in the current tree. */
    private suspend fun findRow(text: String): JsonObject? {
        val result =
            mcpClient.callTool(
                "${TOOL_PREFIX}find_nodes",
                mapOf("by" to "text", "value" to text, "exact_match" to true),
            )
        val nodes = Json.parseToJsonElement(stripUntrustedWarning(textOf(result))).jsonObject["nodes"]?.jsonArray
        return nodes?.firstOrNull()?.jsonObject
    }

    /**
     * Waits for the list to be on screen and settled, then returns the off-screen target row. Node ids hash the bounds
     * and the ancestors: after a launch, even with the window animations off, the list keeps moving (by tens of pixels)
     * and its ancestors change for a while, longer than the launch helper's wait on a slow machine. It settles in one
     * direction and then stays, so a row's id is usable once the ids of the first row and of the target are the same in
     * STABLE_READS reads in a row.
     */
    private suspend fun launchListAndFindTarget(): JsonObject {
        E2ETestApps.launchLongListTestApp()
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        var previousIds: Pair<String, String>? = null
        var stableReads = 0
        var lastRead = "none"
        while (System.currentTimeMillis() < deadline) {
            val first = findRow("Row 0")
            val target = findRow(TARGET_TEXT)
            lastRead = "Row 0: $first, $TARGET_TEXT: $target"
            if (first != null && target != null) {
                val ids = first.nodeId() to target.nodeId()
                stableReads = if (ids == previousIds) stableReads + 1 else 1
                previousIds = ids
                if (stableReads == STABLE_READS) return target
            } else {
                stableReads = 0
                previousIds = null
            }
            Thread.sleep(POLL_MS)
        }
        error("the long list did not settle within ${LAUNCH_TIMEOUT_MS}ms, last read: $lastRead")
    }

    private fun JsonObject.isVisible(): Boolean = getValue("visible").jsonPrimitive.boolean

    private fun JsonObject.nodeId(): String = getValue("node_id").jsonPrimitive.content

    /** Polls until the row's on-screen state is [visible]: a scroll is a smooth animation that ends after the call. */
    private suspend fun waitForRowVisibility(
        text: String,
        visible: Boolean,
    ): JsonObject? {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var row = findRow(text)
        while ((row?.isVisible() ?: false) != visible && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            row = findRow(text)
        }
        return row
    }

    @Test
    @Order(1)
    fun `scroll_to_node by node id brings an off-screen row on screen`() =
        runBlocking {
            val target = launchListAndFindTarget()
            assertEquals(false, target.isVisible(), "$TARGET_TEXT must start off screen: $target")

            val result =
                mcpClient.callTool(
                    "${TOOL_PREFIX}scroll_to_node",
                    mapOf("node_id" to target.nodeId(), "direction" to "down"),
                )

            assertNotEquals(true, result.isError, textOf(result))
            val after = findRow(TARGET_TEXT)
            assertNotNull(after, "$TARGET_TEXT must still be in the tree")
            assertEquals(true, after!!.isVisible(), "$TARGET_TEXT must be on screen after scroll_to_node: $after")
            // Node ids hash the bounds: the result names the row's id after it moved.
            val currentId = after.nodeId()
            assertTrue(
                textOf(result).contains("'$currentId'"),
                "result must name the current id $currentId: ${textOf(result)}",
            )
        }

    @Test
    @Order(2)
    fun `scroll_to_node by selector brings an off-screen row on screen`() =
        runBlocking {
            launchListAndFindTarget()

            val result =
                mcpClient.callTool(
                    "${TOOL_PREFIX}scroll_to_node",
                    mapOf(
                        "by" to "text",
                        "value" to SELECTOR_TARGET_TEXT,
                        "exact_match" to true,
                        "direction" to "down",
                    ),
                )

            assertNotEquals(true, result.isError, textOf(result))
            val after = findRow(SELECTOR_TARGET_TEXT)
            assertNotNull(after, "$SELECTOR_TARGET_TEXT must still be in the tree")
            assertEquals(true, after!!.isVisible(), "$SELECTOR_TARGET_TEXT must be on screen: $after")
            assertTrue(textOf(result).contains("'${after.nodeId()}'"), textOf(result))
        }

    @Test
    @Order(3)
    fun `scroll_node by selector scrolls the list down and back up`() =
        runBlocking {
            launchListAndFindTarget()
            val listSelector = mapOf("by" to "content_desc", "value" to "long_list", "exact_match" to true)

            val down = mcpClient.callTool("${TOOL_PREFIX}scroll_node", listSelector + ("direction" to "down"))
            assertNotEquals(true, down.isError, textOf(down))
            val firstRowAfterDown = waitForRowVisibility("Row 0", visible = false)
            assertEquals(
                false,
                firstRowAfterDown?.isVisible() ?: false,
                "Row 0 must have left the screen: $firstRowAfterDown",
            )

            val up = mcpClient.callTool("${TOOL_PREFIX}scroll_node", listSelector + ("direction" to "up"))
            assertNotEquals(true, up.isError, textOf(up))
            assertEquals(
                true,
                waitForRowVisibility("Row 0", visible = true)?.isVisible(),
                "Row 0 must be back on screen",
            )
        }

    @Test
    @Order(4)
    fun `tap_node by selector taps the single on-screen match`() =
        runBlocking {
            launchListAndFindTarget()
            val rowOneId = findRow("Row 1")!!.nodeId()

            val result =
                mcpClient.callTool(
                    "${TOOL_PREFIX}tap_node",
                    mapOf("by" to "text", "value" to "Row 1", "exact_match" to true),
                )

            assertNotEquals(true, result.isError, textOf(result))
            assertTrue(textOf(result).contains("Tap executed"), textOf(result))
            assertTrue(textOf(result).contains("'$rowOneId'"), "the tap must hit Row 1 ($rowOneId): ${textOf(result)}")
        }

    @Test
    @Order(5)
    fun `an ambiguous selector is an untrusted error listing candidates`() =
        runBlocking {
            launchListAndFindTarget()

            val result =
                mcpClient.callTool(
                    "${TOOL_PREFIX}tap_node",
                    mapOf("by" to "text", "value" to "Row", "exact_match" to false),
                )

            assertEquals(true, result.isError, textOf(result))
            assertTrue(textOf(result).startsWith("CAUTION:"), textOf(result))
            assertTrue(textOf(result).contains("Ambiguous selector"), textOf(result))
        }

    @Test
    @Order(6)
    fun `scroll_to_node stops early when the list cannot scroll that way`() =
        runBlocking {
            launchListAndFindTarget()

            // The list starts at its top: scrolling up cannot reveal the row below and must not keep trying.
            val result =
                mcpClient.callTool(
                    "${TOOL_PREFIX}scroll_to_node",
                    mapOf("by" to "text", "value" to TARGET_TEXT, "exact_match" to true, "direction" to "up"),
                )

            assertEquals(true, result.isError, textOf(result))
            assertTrue(textOf(result).contains("after 0 scroll(s) up"), textOf(result))
            assertEquals(false, findRow(TARGET_TEXT)?.isVisible(), "$TARGET_TEXT must still be off screen")
        }

    private companion object {
        const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        const val TARGET_TEXT = "Row 30"
        const val SELECTOR_TARGET_TEXT = "Row 45"
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val SETTLE_TIMEOUT_MS = 5_000L
        const val POLL_MS = 500L

        /** Reads in a row, each at least POLL_MS after the previous one (over a second in all), with the same ids. */
        const val STABLE_READS = 3

        /** The global settings [E2EScrollToNodeTest.turnOffWindowAnimations] sets to 0. */
        val ANIMATION_SCALES = listOf("window_animation_scale", "transition_animation_scale")
    }
}
