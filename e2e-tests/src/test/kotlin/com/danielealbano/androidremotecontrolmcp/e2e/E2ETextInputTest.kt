package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E tests of the type tools on real text fields (the compose test app's TextFieldActivity, which shows one field):
 * a Compose text field, whose input connection reports no text offset and can lose a DEL key event sent right after a
 * selection, and a classic EditText. Each test runs the same edits and checks the field's text, as the accessibility
 * tree reports it, after every one: appending twice, inserting, replacing, deleting a match and clearing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2ETextInputTest {
    private val mcpClient = SharedAndroidContainer.mcpClient

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    @Test
    fun `type tools edit a Compose text field`() = runBlocking { editSequence("compose") }

    @Test
    fun `type tools edit an EditText`() = runBlocking { editSequence("view") }

    /** Runs the edits on the activity's only field, a [kind] ("compose" or "view") field. */
    private suspend fun editSequence(kind: String) {
        E2ETestApps.launchTextFieldTestApp(kind)
        val field = "$kind field"
        awaitFieldText(field, "")

        typeTool("type_append_text", field, mapOf("text" to "abc"))
        awaitFieldText(field, "abc")

        // A second append must go at the end, not before the last character
        typeTool("type_append_text", field, mapOf("text" to "de"))
        awaitFieldText(field, "abcde")

        typeTool("type_insert_text", field, mapOf("text" to "X", "offset" to 1))
        awaitFieldText(field, "aXbcde")

        typeTool("type_replace_text", field, mapOf("search" to "bc", "new_text" to "Y"))
        awaitFieldText(field, "aXYde")

        typeTool("type_replace_text", field, mapOf("search" to "Y", "new_text" to ""))
        awaitFieldText(field, "aXde")

        typeTool("type_clear_text", field, emptyMap())
        awaitFieldText(field, "")
    }

    /** Calls the type [tool] on the screen's only EditText ([field] names it in messages); the call must succeed. */
    private suspend fun typeTool(
        tool: String,
        field: String,
        arguments: Map<String, Any>,
    ): CallToolResult {
        val selector = mapOf("by" to "class_name", "value" to EDIT_TEXT_CLASS, "exact_match" to true)
        val typing =
            if (tool == "type_clear_text") {
                emptyMap()
            } else {
                mapOf("typing_speed" to TYPING_SPEED_MS, "typing_speed_variance" to 0)
            }
        val result = mcpClient.callTool("${TOOL_PREFIX}$tool", selector + typing + arguments)
        assertNotEquals(true, result.isError, "$tool on $field failed: ${textOf(result)}")
        return result
    }

    /** Polls until the field's text in the accessibility tree is [expected] (an empty field has no text). */
    private suspend fun awaitFieldText(
        field: String,
        expected: String,
    ) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var text = fieldText()
        while (text != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            text = fieldText()
        }
        assertEquals(expected, text, "text of $field")
    }

    /** The text of the screen's only EditText node, "" when it has none, null when there is not exactly one. */
    private suspend fun fieldText(): String? {
        val result =
            mcpClient.callTool(
                "${TOOL_PREFIX}find_nodes",
                mapOf("by" to "class_name", "value" to EDIT_TEXT_CLASS, "exact_match" to true),
            )
        val nodes = Json.parseToJsonElement(stripUntrustedWarning(textOf(result))).jsonObject["nodes"]?.jsonArray
        val node = nodes?.singleOrNull()?.jsonObject ?: return null
        return node.textOrEmpty()
    }

    private fun JsonObject.textOrEmpty(): String = get("text")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun textOf(result: CallToolResult): String = (result.content.firstOrNull() as? TextContent)?.text.orEmpty()

    private companion object {
        const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val TYPING_SPEED_MS = 10
        const val SETTLE_TIMEOUT_MS = 10_000L
        const val POLL_MS = 300L
    }
}
