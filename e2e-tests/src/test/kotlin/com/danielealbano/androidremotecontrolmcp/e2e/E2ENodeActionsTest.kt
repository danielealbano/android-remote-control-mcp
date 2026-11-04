package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test: node accessibility actions
 *
 * The compose test app's vote bar is one node whose only semantics are the custom actions "Upvote" and "Downvote",
 * with no text, content description, test tag or click, as on Reddit's vote bar. It must be listed under
 * "Nodes with actions" only thanks to its actions, `find_nodes` and `get_node_details` must show the actions, and
 * `perform_node_action` must run them: the "Votes: <n>" counter beside the bar changes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2ENodeActionsTest {
    private val mcpClient = SharedAndroidContainer.mcpClient

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 500L

        /** The vote bar's line in the actions section. */
        private val VOTE_BAR_LINE = Regex("^\\* (node_[0-9a-f]+): Upvote¦Downvote$", RegexOption.MULTILINE)
    }

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    @Test
    fun `a node listed only for its actions shows them and perform_node_action runs them`() =
        runBlocking {
            mcpClient.callTool("${TOOL_PREFIX}press_home")
            Thread.sleep(1_000)
            E2ETestApps.launchCustomActionsTestApp()

            val screen = waitForScreenState { it.contains("Votes: 0") && VOTE_BAR_LINE.containsMatchIn(it) }
            assertNotNull(screen, "the vote bar should be listed under 'Nodes with actions' within ${TIMEOUT_MS}ms")
            val barId = VOTE_BAR_LINE.find(screen!!)!!.groupValues[1]

            // Listed only for its actions: no text, description or resource id, and not clickable.
            val columns = screen.lines().single { it.startsWith("$barId\t") }.split('\t')
            assertEquals(listOf("-", "-", "-"), columns.subList(2, 5), "row: $columns")
            assertFalse(columns[6].split(',').contains("clk"), "row: $columns")
            assertTrue(columns[6].startsWith("on"), "row: $columns")

            val details =
                textOf(mcpClient.callTool("${TOOL_PREFIX}get_node_details", mapOf("node_ids" to listOf(barId))))
            assertEquals("$barId\t-\t-\tfalse\tUpvote¦Downvote", details.lines()[1])

            val found =
                textOf(
                    mcpClient.callTool(
                        "${TOOL_PREFIX}find_nodes",
                        mapOf("by" to "class_name", "value" to "android.view.View", "exact_match" to true),
                    ),
                )
            val bar =
                Json
                    .parseToJsonElement(found)
                    .jsonObject["nodes"]!!
                    .jsonArray
                    .single { it.jsonObject["node_id"]!!.jsonPrimitive.content == barId }
            assertEquals(
                listOf("Upvote", "Downvote"),
                bar.jsonObject["actions"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val upvote =
                mcpClient.callTool(
                    "${TOOL_PREFIX}perform_node_action",
                    mapOf("node_id" to barId, "action" to "Upvote"),
                )
            assertTrue(upvote.isError != true, textOf(upvote))
            assertEquals("Action 'Upvote' performed on node '$barId'", textOf(upvote))
            assertNotNull(waitForScreenState { it.contains("Votes: 1") }, "Upvote should raise the counter to 1")

            val downvote =
                mcpClient.callTool(
                    "${TOOL_PREFIX}perform_node_action",
                    mapOf("node_id" to barId, "action" to "Downvote"),
                )
            assertTrue(downvote.isError != true, textOf(downvote))
            assertNotNull(waitForScreenState { it.contains("Votes: 0") }, "Downvote should bring the counter to 0")

            val unknown =
                mcpClient.callTool(
                    "${TOOL_PREFIX}perform_node_action",
                    mapOf("node_id" to barId, "action" to "Repost"),
                )
            assertEquals(true, unknown.isError)
            assertTrue(textOf(unknown).endsWith("Its actions: Upvote¦Downvote"), textOf(unknown))
        }

    private fun textOf(result: CallToolResult): String = stripUntrustedWarning((result.content[0] as TextContent).text)

    /** Polls `get_screen_state` until [condition] holds, returning that output, or null after [TIMEOUT_MS]. */
    private suspend fun waitForScreenState(condition: (String) -> Boolean): String? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
            try {
                val result = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
                if (result.isError != true) {
                    val text = textOf(result)
                    if (condition(text)) return text
                }
            } catch (_: Exception) {
                // Transient error, retry
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }
}
