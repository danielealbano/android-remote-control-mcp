package com.danielealbano.androidremotecontrolmcp.integration

import android.os.SystemClock
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.mcp.tools.stripUntrustedWarning
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.FakeTextField
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every tool that returns text read from an accessibility node redacts it with that node's own detection context
 * (resource id, hint, label, password and editable flags), like get_screen_state: a credential field must not come
 * back in clear from get_node_details, find_nodes, wait_for_node, an ambiguous-selector list or the type tools. The
 * labels of a node's accessibility actions are redacted the same way, and perform_node_action matches them as shown.
 */
@DisplayName("Privacy Mode node context Integration Tests")
class PrivacyModeNodeContextIntegrationTest {
    private val screenInfo = ScreenInfo(width = 1080, height = 2400, densityDpi = 420, orientation = "portrait")

    // Deterministic-only config: model-backed categories disabled so no model is required.
    private val deterministicEnabled =
        PrivacyModeConfig(
            enabled = true,
            disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
        )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    /**
     * An editable field that is not a password field but whose resource id names a credential. No detector matches
     * the bare value: only the node's context (resource id + editable) makes it a credential, as in get_screen_state.
     */
    private fun apiTokenField(id: String = "node_token") =
        AccessibilityNodeData(
            id = id,
            className = "android.widget.EditText",
            text = API_TOKEN_VALUE,
            resourceId = "com.example.app:id/api_token",
            bounds = BoundsData(100, 200, 900, 260),
            editable = true,
            focusable = true,
            enabled = true,
            visible = true,
        )

    private fun passwordField(text: String?) =
        AccessibilityNodeData(
            id = "node_pwd",
            className = "android.widget.EditText",
            text = text,
            bounds = BoundsData(50, 800, 500, 900),
            editable = true,
            focusable = true,
            enabled = true,
            visible = true,
            isPassword = true,
        )

    private fun treeOf(vararg nodes: AccessibilityNodeData) =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            enabled = true,
            children = nodes.toList(),
        )

    private fun AccessibilityNodeData.toElementInfo() =
        ElementInfo(
            id = id,
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            className = className,
            bounds = bounds,
            editable = editable,
            enabled = enabled,
            visible = visible,
            password = isPassword,
            actions = actions,
        )

    private fun privacyDeps(
        tree: AccessibilityNodeData,
        focusedNodeId: String? = null,
    ): MockDependencies {
        val deps = McpIntegrationTestHelper.createMockDependencies()
        McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
        val rootNode =
            McpIntegrationTestHelper.setupMultiWindowMock(
                deps = deps,
                tree = tree,
                screenInfo = screenInfo,
                packageName = "com.example.app",
                activityName = ".MainActivity",
            )
        focusedNodeId?.let { McpIntegrationTestHelper.focusInputOn(deps, rootNode, it) }
        return deps
    }

    /**
     * A focused field whose input connection holds [initial], the real characters as for a password field (see
     * [FakeTextField]); clicks on any node succeed.
     */
    private fun MockDependencies.inputFieldWith(
        initial: String,
        loseDeletions: Boolean = false,
    ): FakeTextField {
        coEvery { actionExecutor.clickNode(any(), any<List<WindowData>>()) } returns Result.success(Unit)
        return FakeTextField(typeInputController, initial, loseDeletions = loseDeletions)
    }

    private fun assertCredentialRedacted(
        text: String,
        secret: String = API_TOKEN_VALUE,
    ) {
        assertFalse(text.contains(secret), text)
        assertTrue(text.contains("CREDENTIAL#"), text)
    }

    @Test
    fun `get_screen_state redacts a credential field from its node context`() =
        runTest {
            val deps = privacyDeps(treeOf(apiTokenField()))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                assertCredentialRedacted((result.content[0] as TextContent).text)
            }
        }

    @Test
    fun `get_node_details redacts a credential field from its node context`() =
        runTest {
            val field = apiTokenField()
            val deps = privacyDeps(treeOf(field))
            every { deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_token") } returns field

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_node_details",
                        arguments = mapOf("node_ids" to listOf("node_token")),
                    )
                assertNotEquals(true, result.isError)
                assertCredentialRedacted((result.content[0] as TextContent).text)
            }
        }

    @Test
    fun `get_node_details redacts the text of a password field`() =
        runTest {
            val field = passwordField("hunter2secret")
            val deps = privacyDeps(treeOf(field))
            every { deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_pwd") } returns field

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_node_details",
                        arguments = mapOf("node_ids" to listOf("node_pwd")),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertCredentialRedacted(text, secret = "hunter2secret")
                assertTrue(stripUntrustedWarning(text).lines()[1].endsWith("\ttrue\t-"), text)
            }
        }

    @Test
    fun `find_nodes redacts a credential field from its node context`() =
        runTest {
            val field = apiTokenField()
            val deps = privacyDeps(treeOf(field))
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), any(), any(), any())
            } returns listOf(field.toElementInfo())

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_find_nodes",
                        arguments = mapOf("by" to "resource_id", "value" to "api_token"),
                    )
                assertNotEquals(true, result.isError)
                assertCredentialRedacted((result.content[0] as TextContent).text)
            }
        }

    /** A contact row whose only controls are actions, one of them naming an e-mail address. */
    private val contactRow =
        AccessibilityNodeData(
            id = "node_contact",
            className = "android.view.View",
            contentDescription = "Contact",
            bounds = BoundsData(0, 300, 1080, 400),
            enabled = true,
            visible = true,
            actions = listOf("Mail $EMAIL_VALUE", "Share"),
        )

    private fun assertEmailRedacted(text: String) {
        assertFalse(text.contains(EMAIL_VALUE), text)
        assertTrue(text.contains("EMAIL"), text)
    }

    @Test
    fun `get_screen_state redacts action labels`() =
        runTest {
            val deps = privacyDeps(treeOf(contactRow))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEmailRedacted(text)
                assertTrue(text.contains("* node_contact: Mail "), text)
                assertTrue(text.contains("¦Share"), text)
            }
        }

    @Test
    fun `find_nodes and get_node_details redact action labels`() =
        runTest {
            val deps = privacyDeps(treeOf(contactRow))
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), any(), any(), any())
            } returns listOf(contactRow.toElementInfo())
            every { deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_contact") } returns contactRow

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val found =
                    client.callTool(
                        name = "android_find_nodes",
                        arguments = mapOf("by" to "content_desc", "value" to "Contact"),
                    )
                assertEmailRedacted((found.content[0] as TextContent).text)

                val details =
                    client.callTool(
                        name = "android_get_node_details",
                        arguments = mapOf("node_ids" to listOf("node_contact")),
                    )
                assertEmailRedacted((details.content[0] as TextContent).text)
            }
        }

    @Test
    fun `perform_node_action matches the redacted label and performs the unredacted one`() =
        runTest {
            val deps = privacyDeps(treeOf(contactRow))
            every { deps.elementFinder.findNodeById(any<List<WindowData>>(), "node_contact") } returns contactRow
            coEvery {
                deps.actionExecutor.performLabelledAction("node_contact", any<List<WindowData>>(), "Mail $EMAIL_VALUE")
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val details =
                    client.callTool(
                        name = "android_get_node_details",
                        arguments = mapOf("node_ids" to listOf("node_contact")),
                    )
                val shownLabel =
                    stripUntrustedWarning((details.content[0] as TextContent).text)
                        .lines()[1]
                        .substringAfterLast('\t')
                        .split('¦')
                        .first()
                assertTrue(shownLabel.startsWith("Mail ") && !shownLabel.contains(EMAIL_VALUE), shownLabel)

                val result =
                    client.callTool(
                        name = "android_perform_node_action",
                        arguments = mapOf("node_id" to "node_contact", "action" to shownLabel),
                    )

                assertNotEquals(true, result.isError, (result.content[0] as TextContent).text)
                assertEquals(
                    "Action '$shownLabel' performed on node 'node_contact'",
                    (result.content[0] as TextContent).text,
                )
            }
            coVerify(exactly = 1) {
                deps.actionExecutor.performLabelledAction("node_contact", any<List<WindowData>>(), "Mail $EMAIL_VALUE")
            }
        }

    @Test
    fun `wait_for_node redacts a credential field from its node context`() =
        runTest {
            mockkStatic(SystemClock::class)
            try {
                every { SystemClock.elapsedRealtime() } returns 0L
                val field = apiTokenField()
                val deps = McpIntegrationTestHelper.createMockDependencies()
                McpIntegrationTestHelper.setPrivacy(
                    deps,
                    deterministicEnabled,
                    PrivacyModeStatus.ReadyDeterministicOnly,
                )
                val rootNode =
                    McpIntegrationTestHelper.setupMultiWindowMock(
                        deps = deps,
                        tree = treeOf(field),
                        screenInfo = screenInfo,
                        packageName = "com.example.app",
                        activityName = ".MainActivity",
                    )
                // The raw poll matches on the root node's text, then the full parse finds the field.
                every { rootNode.text } returns API_TOKEN_VALUE
                every {
                    deps.elementFinder.findElements(any<List<WindowData>>(), any(), any(), any())
                } returns listOf(field.toElementInfo())

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_wait_for_node",
                            arguments = mapOf("by" to "text", "value" to API_TOKEN_VALUE, "timeout" to 5000),
                        )
                    assertNotEquals(true, result.isError)
                    assertCredentialRedacted((result.content[0] as TextContent).text)
                }
            } finally {
                unmockkStatic(SystemClock::class)
            }
        }

    @Test
    fun `ambiguous selector candidates are redacted from their node context`() =
        runTest {
            val first = apiTokenField("node_token_1")
            val second = apiTokenField("node_token_2").copy(bounds = BoundsData(100, 300, 900, 360))
            val deps = privacyDeps(treeOf(first, second))
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), any(), any(), any())
            } returns listOf(first.toElementInfo(), second.toElementInfo())

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_node",
                        arguments = mapOf("by" to "resource_id", "value" to "api_token"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("node_token_2"), text)
                assertCredentialRedacted(text)
            }
        }

    @Test
    fun `wait_for_node redacts action labels`() =
        runTest {
            mockkStatic(SystemClock::class)
            try {
                every { SystemClock.elapsedRealtime() } returns 0L
                val deps = McpIntegrationTestHelper.createMockDependencies()
                McpIntegrationTestHelper.setPrivacy(
                    deps,
                    deterministicEnabled,
                    PrivacyModeStatus.ReadyDeterministicOnly,
                )
                val rootNode =
                    McpIntegrationTestHelper.setupMultiWindowMock(
                        deps = deps,
                        tree = treeOf(contactRow),
                        screenInfo = screenInfo,
                        packageName = "com.example.app",
                        activityName = ".MainActivity",
                    )
                // The raw poll matches on the root node's content description, then the full parse finds the row.
                every { rootNode.contentDescription } returns "Contact"
                every {
                    deps.elementFinder.findElements(any<List<WindowData>>(), any(), any(), any())
                } returns listOf(contactRow.toElementInfo())

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_wait_for_node",
                            arguments = mapOf("by" to "content_desc", "value" to "Contact", "timeout" to 5000),
                        )
                    assertNotEquals(true, result.isError)
                    val text = (result.content[0] as TextContent).text
                    assertTrue(text.contains("\"actions\":[\"Mail "), text)
                    assertEmailRedacted(text)
                }
            } finally {
                unmockkStatic(SystemClock::class)
            }
        }

    @Test
    fun `ambiguous selector candidates redact action labels`() =
        runTest {
            val second = contactRow.copy(id = "node_contact_2", bounds = BoundsData(0, 420, 1080, 520))
            val deps = privacyDeps(treeOf(contactRow, second))
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), any(), any(), any())
            } returns listOf(contactRow.toElementInfo(), second.toElementInfo())

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_perform_node_action",
                        arguments = mapOf("by" to "content_desc", "value" to "Contact", "action" to "Share"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("node_contact_2") && text.contains("\"actions\":[\"Mail "), text)
                assertEmailRedacted(text)
            }
            coVerify(exactly = 0) { deps.actionExecutor.performLabelledAction(any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────
    // Type tools: "Field content" is the focused field's, read through the input connection
    // ─────────────────────────────────────────────────────────────────────

    /** Calls the type [tool] through the MCP stack and returns its text, asserting it succeeded. */
    private suspend fun typeToolResult(
        deps: MockDependencies,
        tool: String,
        arguments: Map<String, Any>,
    ): String {
        var text = ""
        McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
            val result = client.callTool(name = tool, arguments = arguments)
            text = (result.content[0] as TextContent).text
            assertNotEquals(true, result.isError, text)
        }
        return text
    }

    @Test
    fun `type_append_text redacts the content of a password field`() =
        runTest {
            val deps = privacyDeps(treeOf(passwordField(text = null)), focusedNodeId = "node_pwd")
            val field = deps.inputFieldWith("hunter2secret")

            val text = typeToolResult(deps, "android_type_append_text", mapOf("node_id" to "node_pwd", "text" to "9"))

            assertEquals("hunter2secret9", field.content)
            assertCredentialRedacted(text, secret = "hunter2secret")
        }

    @Test
    fun `type_insert_text redacts the content of a password field`() =
        runTest {
            val deps = privacyDeps(treeOf(passwordField(text = null)), focusedNodeId = "node_pwd")
            deps.inputFieldWith("hunter2secret")

            val text =
                typeToolResult(
                    deps,
                    "android_type_insert_text",
                    mapOf("node_id" to "node_pwd", "text" to "9", "offset" to 0),
                )

            assertCredentialRedacted(text, secret = "hunter2secret")
        }

    @Test
    fun `type_replace_text redacts the content of a password field`() =
        runTest {
            val deps = privacyDeps(treeOf(passwordField(text = null)), focusedNodeId = "node_pwd")
            deps.inputFieldWith("hunter2secret")

            val text =
                typeToolResult(
                    deps,
                    "android_type_replace_text",
                    mapOf("node_id" to "node_pwd", "search" to "secret", "new_text" to "9"),
                )

            assertCredentialRedacted(text, secret = "hunter2")
        }

    @Test
    fun `type_clear_text never returns the content of a password field it could not clear`() =
        runTest {
            val deps = privacyDeps(treeOf(passwordField(text = null)), focusedNodeId = "node_pwd")
            // The field loses the deletion: the tool fails, and its error carries no field content
            deps.inputFieldWith("hunter2secret", loseDeletions = true)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(name = "android_type_clear_text", arguments = mapOf("node_id" to "node_pwd"))
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("was not deleted"), text)
                assertFalse(text.contains("hunter2"), text)
            }
        }

    @Test
    fun `type_clear_text redacts what a password field reads back after the clear`() =
        runTest {
            val deps = privacyDeps(treeOf(passwordField(text = null)), focusedNodeId = "node_pwd")
            // The field fills itself again right after the clear, with the real characters of a password
            val field = deps.inputFieldWith("hunter2secret")
            field.reformat = { it.ifEmpty { "hunter2refill" } }

            val text = typeToolResult(deps, "android_type_clear_text", mapOf("node_id" to "node_pwd"))

            assertEquals("hunter2refill", field.content)
            assertCredentialRedacted(text, secret = "hunter2refill")
        }

    @Test
    fun `type tools redact as a password when the focused editor is a password input`() =
        runTest {
            // The node does not report isPassword (and has no credential context): only the editor's input type does.
            val plainField = passwordField(text = null).copy(id = "node_plain", isPassword = false)
            val deps = privacyDeps(treeOf(plainField), focusedNodeId = "node_plain")
            deps.inputFieldWith("hunter2secret")
            every { deps.typeInputController.isPasswordInput() } returns true

            val text =
                typeToolResult(
                    deps,
                    "android_type_append_text",
                    mapOf("node_id" to "node_plain", "text" to "9"),
                )

            assertCredentialRedacted(text, secret = "hunter2secret")
        }

    @Test
    fun `type tools redact with the context of the focused field inside the target`() =
        runTest {
            // The target is a wrapper that hands input focus to the password field inside it.
            val wrapper =
                AccessibilityNodeData(
                    id = "node_wrapper",
                    className = "android.widget.FrameLayout",
                    bounds = BoundsData(50, 800, 500, 900),
                    clickable = true,
                    enabled = true,
                    visible = true,
                    children = listOf(passwordField(text = null)),
                )
            val deps = privacyDeps(treeOf(wrapper), focusedNodeId = "node_pwd")
            val field = deps.inputFieldWith("hunter2secret")

            val text =
                typeToolResult(
                    deps,
                    "android_type_append_text",
                    mapOf("node_id" to "node_wrapper", "text" to "9"),
                )

            assertEquals("hunter2secret9", field.content)
            assertCredentialRedacted(text, secret = "hunter2secret")
        }

    @Test
    fun `type_append_text types nothing when another field keeps input focus`() =
        runTest {
            // Clicking the toggle leaves input focus on the password field next to it.
            val toggle =
                AccessibilityNodeData(
                    id = "node_toggle",
                    className = "android.widget.ImageButton",
                    contentDescription = "Show password",
                    bounds = BoundsData(520, 800, 600, 900),
                    clickable = true,
                    enabled = true,
                    visible = true,
                )
            val deps = privacyDeps(treeOf(passwordField(text = null), toggle), focusedNodeId = "node_pwd")
            val field = deps.inputFieldWith("hunter2secret")
            // The toggle accepts the focus request, but input focus stays on the password field.
            coEvery { deps.actionExecutor.focusNode("node_toggle", any()) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_append_text",
                        arguments = mapOf("node_id" to "node_toggle", "text" to "9"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Input focus is not on node 'node_toggle'"), text)
                assertFalse(text.contains("hunter2secret"), text)
            }
            assertEquals("hunter2secret", field.content)
            verify(exactly = 0) { deps.typeInputController.commitText(any(), any()) }
        }

    private companion object {
        const val API_TOKEN_VALUE = "sk-live-7Hq2Lw9Zx"
        const val EMAIL_VALUE = "ada.lovelace@example.com"
    }
}
