package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.every
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("GetNodeScreenshotsTool")
class GetNodeScreenshotsToolTest {
    /** R0 whole at the top of a list, R9 cut at its bottom until shown. */
    private val device =
        FakeDevice(
            screen(list(reach(down = true), row("R0", 200), row("R9", 1300))),
            screen(list(reach(up = true, down = true), row("R9", 1200))),
        )
    private val tool = GetNodeScreenshotsTool(device.deps)

    private fun args(
        vararg ids: String,
        allowScroll: Boolean? = null,
    ): JsonObject =
        buildJsonObject {
            putJsonArray("node_ids") { ids.forEach { add(it) } }
            allowScroll?.let { put("allow_scroll", it) }
        }

    private suspend fun invalid(arguments: JsonObject?): String? =
        assertThrows<McpToolException.InvalidParams> { tool.execute(arguments) }.message

    @Test
    fun `rejects a missing, non-array or empty node_ids`() =
        runTest {
            assertEquals("Missing required parameter 'node_ids'", invalid(buildJsonObject {}))
            assertEquals(
                "Parameter 'node_ids' must be an array of strings",
                invalid(buildJsonObject { put("node_ids", "R0") }),
            )
            assertEquals("Parameter 'node_ids' must not be empty", invalid(args()))
        }

    @Test
    fun `rejects a non-string node id`() =
        runTest {
            val arguments = buildJsonObject { putJsonArray("node_ids") { add(7) } }

            assertEquals("Each element in 'node_ids' must be a string", invalid(arguments))
        }

    @Test
    fun `rejects more than 100 node ids`() =
        runTest {
            val ids = Array(101) { "n$it" }

            assertEquals("Parameter 'node_ids' must have at most 100 node ids", invalid(args(*ids)))
        }

    @Test
    fun `rejects a duplicate node id`() =
        runTest {
            assertEquals("Parameter 'node_ids' must not contain duplicates: 'R0'", invalid(args("R0", "R9", "R0")))
        }

    @Test
    fun `rejects an allow_scroll that is not a boolean`() =
        runTest {
            val arguments =
                buildJsonObject {
                    putJsonArray("node_ids") { add("R0") }
                    put("allow_scroll", "yes")
                }

            invalid(arguments)
        }

    @Test
    fun `screen capture unavailable is refused before any read`() =
        runTest {
            every { device.screenCaptureProvider.isScreenCaptureAvailable() } returns false

            assertThrows<McpToolException.PermissionDenied> { tool.execute(args("R0")) }

            verify(exactly = 0) { device.treeParser.parseTree(any(), any(), any()) }
        }

    @Test
    fun `the untrusted content warning comes first, then the lines and the images`() =
        runTest {
            val result = tool.execute(args("R9", "R0"))

            val text = (result.content[0] as TextContent).text
            assertEquals(
                listOf(McpToolUtils.UNTRUSTED_CONTENT_WARNING, "R9: image 1, $PARTIAL_TEXT", "R0: image 2"),
                text.split("\n"),
            )
            assertEquals(listOf("c1/R9", "c1/R0"), result.content.drop(1).map { (it as ImageContent).data })
            assertEquals("image/jpeg", (result.content[1] as ImageContent).mimeType)
        }

    @Test
    fun `the scrolled note follows the warning when the app was scrolled`() =
        runTest {
            val result = tool.execute(args("R9", allowScroll = true))

            val lines = (result.content[0] as TextContent).text.split("\n")
            assertEquals(listOf(McpToolUtils.UNTRUSTED_CONTENT_WARNING, SCROLLED_NOTE, "R9: image 1"), lines)
        }
}
