package com.danielealbano.androidremotecontrolmcp.integration

import android.os.SystemClock
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.mcp.tools.stripUntrustedWarning
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
 * back in clear from get_node_details, find_nodes, wait_for_node, an ambiguous-selector list or the type tools.
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
        )

    private fun privacyDeps(tree: AccessibilityNodeData): MockDependencies {
        val deps = McpIntegrationTestHelper.createMockDependencies()
        McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
        McpIntegrationTestHelper.setupMultiWindowMock(
            deps = deps,
            tree = tree,
            screenInfo = screenInfo,
            packageName = "com.example.app",
            activityName = ".MainActivity",
        )
        return deps
    }

    private fun assertCredentialRedacted(
        text: String,
        secret: String = API_TOKEN_VALUE,
    ) {
        assertFalse(text.contains(secret), text)
        assertTrue(text.contains("CREDENTIAL#"), text)
    }

    private fun mockSurroundingText(text: String): SurroundingText {
        val mock = mockk<SurroundingText>()
        every { mock.text } returns text
        every { mock.offset } returns 0
        every { mock.selectionStart } returns text.length
        every { mock.selectionEnd } returns text.length
        return mock
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
                assertTrue(stripUntrustedWarning(text).lines()[1].endsWith("\ttrue"), text)
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
    fun `type_append_text redacts the content of a password field`() =
        runTest {
            val deps = privacyDeps(treeOf(passwordField(text = null)))
            coEvery { deps.actionExecutor.clickNode("node_pwd", any<List<WindowData>>()) } returns Result.success(Unit)
            every { deps.typeInputController.isReady() } returns true
            every { deps.typeInputController.setSelection(any(), any()) } returns true
            // The input connection hands the real characters of a password field, unlike the accessibility tree.
            val content = StringBuilder("hunter2secret")
            every { deps.typeInputController.getSurroundingText(any(), any(), any()) } answers {
                mockSurroundingText(content.toString())
            }
            every { deps.typeInputController.commitText(any(), any()) } answers {
                content.append(firstArg<String>())
                true
            }

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_append_text",
                        arguments = mapOf("node_id" to "node_pwd", "text" to "9"),
                    )
                assertNotEquals(true, result.isError)
                assertEquals("hunter2secret9", content.toString())
                assertCredentialRedacted((result.content[0] as TextContent).text, secret = "hunter2secret")
            }
        }

    private companion object {
        const val API_TOKEN_VALUE = "sk-live-7Hq2Lw9Zx"
    }
}
