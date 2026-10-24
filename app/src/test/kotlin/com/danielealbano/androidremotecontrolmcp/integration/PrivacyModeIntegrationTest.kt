@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.tools.stripUntrustedWarning
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerResult
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Privacy Mode Egress Integration Tests")
class PrivacyModeIntegrationTest {
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

    private fun treeWith(
        text: String,
        isPassword: Boolean = false,
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            enabled = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_field",
                        className = "android.widget.TextView",
                        text = text,
                        bounds = BoundsData(100, 200, 900, 260),
                        visible = true,
                        enabled = true,
                        isPassword = isPassword,
                    ),
                ),
        )

    private fun MockDependencies.setupTree(tree: AccessibilityNodeData) {
        McpIntegrationTestHelper.setupMultiWindowMock(
            deps = this,
            tree = tree,
            screenInfo = screenInfo,
            packageName = "com.example.app",
            activityName = ".MainActivity",
        )
    }

    @Test
    fun `privacy disabled leaves output untouched`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            deps.setupTree(treeWith("john@example.com"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("john@example.com"))
            }
        }

    @Test
    fun `enabled redacts email in tree output`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            deps.setupTree(treeWith("john@example.com"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("EMAIL#"))
                assertFalse(text.contains("john@example.com"))
            }
        }

    @Test
    fun `redact mode renders redacted marker`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(
                deps,
                deterministicEnabled.copy(redactionMode = RedactionMode.REDACT),
                PrivacyModeStatus.ReadyDeterministicOnly,
            )
            deps.setupTree(treeWith("john@example.com"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("[REDACTED:EMAIL]"))
                assertFalse(text.contains("john@example.com"))
            }
        }

    @Test
    fun `numbered format stable across two calls`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(
                deps,
                deterministicEnabled.copy(placeholderFormat = PlaceholderFormat.NUMBERED),
                PrivacyModeStatus.ReadyDeterministicOnly,
            )
            deps.setupTree(treeWith("john@example.com"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val first = screenStateText(client)
                val second = screenStateText(client)
                assertTrue(first.contains("[EMAIL_1]"))
                assertTrue(second.contains("[EMAIL_1]"))
            }
        }

    @Test
    fun `password node text suppressed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            deps.setupTree(treeWith("hunter2secret", isPassword = true))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertFalse(text.contains("hunter2secret"))
            }
        }

    @Test
    fun `fail closed returns error not data`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            // All categories enabled (NAMES requires the model) but the model is unavailable.
            McpIntegrationTestHelper.setPrivacy(
                deps,
                PrivacyModeConfig(enabled = true),
                PrivacyModeStatus.Unavailable("model file missing"),
            )
            deps.setupTree(treeWith("john@example.com"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Privacy mode"))
                assertFalse(text.contains("john@example.com"))
            }
        }

    @Test
    fun `notification_list titles redacted`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            every { deps.notificationProvider.isReady() } returns true
            coEvery { deps.notificationProvider.getNotifications(null, null) } returns
                listOf(
                    NotificationData(
                        notificationId = "aabbcc01",
                        packageName = "com.example.app",
                        appName = "Example",
                        title = "Mail from john@example.com",
                        text = "Body",
                        bigText = null,
                        subText = null,
                        timestamp = 1_700_000_000_000L,
                        isOngoing = false,
                        isClearable = true,
                        category = null,
                        groupKey = null,
                        actions = emptyList(),
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_notification_list", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("EMAIL#"))
                assertFalse(text.contains("john@example.com"))
            }
        }

    @Test
    fun `get_clipboard redacted`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            val mockContext = mockk<Context>()
            val mockClipboardManager = mockk<ClipboardManager>()
            val mockClipData = mockk<ClipData>()
            val mockItem = mockk<ClipData.Item>()
            every { deps.accessibilityServiceProvider.getContext() } returns mockContext
            every { mockContext.getSystemService(ClipboardManager::class.java) } returns mockClipboardManager
            every { mockClipboardManager.primaryClip } returns mockClipData
            every { mockClipData.itemCount } returns 1
            every { mockClipData.getItemAt(0) } returns mockItem
            every { mockItem.text } returns "reach me at john@example.com"

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_clipboard", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
                val clip = parsed["text"]?.jsonPrimitive?.content ?: ""
                assertTrue(clip.contains("EMAIL#"))
                assertFalse(clip.contains("john@example.com"))
            }
        }

    @Test
    fun `find_nodes substitutes placeholder argument`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            deps.setupTree(treeWith("placeholder-search-anchor"))

            // Seed the pseudonym store so a placeholder maps back to the original email.
            val placeholder = deps.privacyToolGate.text("john@example.com", "seed")
            assertTrue(placeholder!!.startsWith("EMAIL#"))

            val valueSlot = slot<String>()
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), any(), capture(valueSlot), any())
            } returns emptyList()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_find_nodes",
                        arguments = mapOf("by" to "text", "value" to placeholder),
                    )
                assertNotEquals(true, result.isError)
                assertEquals("john@example.com", valueSlot.captured)
            }
        }

    @Test
    fun `node selector reverses the placeholder and redacts the ambiguous candidates`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            deps.setupTree(treeWith("john@example.com"))
            val placeholder = deps.privacyToolGate.text("john@example.com", "seed")!!

            val valueSlot = slot<String>()
            val match =
                ElementInfo(
                    id = "node_field",
                    text = "john@example.com",
                    className = "android.widget.TextView",
                    bounds = BoundsData(100, 200, 900, 260),
                    visible = true,
                )
            every {
                deps.elementFinder.findElements(any<List<WindowData>>(), any(), capture(valueSlot), any())
            } returns listOf(match, match.copy(id = "node_field_2"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_click_node",
                        arguments = mapOf("by" to "text", "value" to placeholder),
                    )
                assertEquals(true, result.isError)
                assertEquals("john@example.com", valueSlot.captured)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("node_field_2"), text)
                assertTrue(text.contains("EMAIL#"), text)
                assertFalse(text.contains("john@example.com"), text)
            }
        }

    @Test
    fun `type_append_text substitutes placeholder back`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)

            val editTree =
                AccessibilityNodeData(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    visible = true,
                    children =
                        listOf(
                            AccessibilityNodeData(
                                id = "node_edit",
                                className = "android.widget.EditText",
                                text = "",
                                bounds = BoundsData(50, 800, 500, 900),
                                editable = true,
                                focusable = true,
                                enabled = true,
                                visible = true,
                            ),
                        ),
                )
            deps.setupTree(editTree)
            coEvery { deps.actionExecutor.clickNode("node_edit", any<List<WindowData>>()) } returns Result.success(Unit)
            every { deps.typeInputController.isReady() } returns true
            every { deps.typeInputController.setSelection(any(), any()) } returns true

            // General reads (cursor positioning + initial length) see an empty field.
            every { deps.typeInputController.getSurroundingText(any(), any(), any()) } returns mockSurroundingText("")

            // Per-character verification reads back what has been committed so far.
            val committed = StringBuilder()
            every { deps.typeInputController.commitText(any(), any()) } answers {
                committed.append(firstArg<String>())
                true
            }
            every { deps.typeInputController.getSurroundingText(any(), eq(0), eq(0)) } answers {
                mockSurroundingText(committed.toString())
            }

            // Seed the pseudonym store so the placeholder reverses to the real value.
            val placeholder = deps.privacyToolGate.text("john@example.com", "seed")!!

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_type_append_text",
                        arguments = mapOf("node_id" to "node_edit", "text" to placeholder),
                    )
                assertNotEquals(true, result.isError)
                assertEquals("john@example.com", committed.toString())
            }
        }

    @Test
    fun `model detections redact names`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, PrivacyModeConfig(enabled = true), PrivacyModeStatus.Ready)
            deps.setupTree(treeWith("John Smith"))
            coEvery { deps.piiModelInference.infer(any()) } answers {
                firstArg<List<NerSegment>>().map { segment ->
                    NerResult(
                        segment.key,
                        listOf(PiiDetection(PiiCategory.NAMES, 0, segment.text.length, PiiDetection.Source.MODEL)),
                    )
                }
            }

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("NAME#"))
                assertFalse(text.contains("John Smith"))
            }
        }

    @Test
    fun `screenshot masking invoked with flagged bounds`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            deps.setupTree(treeWith("john@example.com"))
            every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true

            val resized = mockk<Bitmap>(relaxed = true)
            val masked = mockk<Bitmap>(relaxed = true)
            coEvery { deps.screenCaptureProvider.captureScreenshotBitmap(any(), any()) } returns Result.success(resized)
            every { resized.copy(Bitmap.Config.ARGB_8888, true) } returns masked
            every { masked.width } returns 1080
            every { masked.height } returns 2400

            mockkConstructor(Canvas::class)
            every { anyConstructed<Canvas>().drawRect(any<RectF>(), any()) } just Runs

            val annotated = mockk<Bitmap>(relaxed = true)
            every { deps.screenshotAnnotator.annotate(masked, any(), any(), any()) } returns annotated
            every { deps.screenshotEncoder.bitmapToScreenshotData(any(), any()) } returns
                ScreenshotData(data = "dGVzdA==", width = 700, height = 500)

            try {
                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_get_screen_state",
                            arguments = mapOf("include_screenshot" to true),
                        )
                    assertNotEquals(true, result.isError)
                }
                // The masked copy (not the raw captured bitmap) must be the one annotated/encoded.
                verify { deps.screenshotAnnotator.annotate(masked, any(), 1080, 2400) }
            } finally {
                unmockkConstructor(Canvas::class)
            }
        }

    @Test
    fun `screenshot with annotate false encodes the masked copy`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)
            deps.setupTree(treeWith("john@example.com"))
            every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true

            val resized = mockk<Bitmap>(relaxed = true)
            val masked = mockk<Bitmap>(relaxed = true)
            coEvery { deps.screenCaptureProvider.captureScreenshotBitmap(any(), any()) } returns Result.success(resized)
            every { resized.copy(Bitmap.Config.ARGB_8888, true) } returns masked
            every { masked.width } returns 1080
            every { masked.height } returns 2400

            mockkConstructor(Canvas::class)
            every { anyConstructed<Canvas>().drawRect(any<RectF>(), any()) } just Runs

            every { deps.screenshotEncoder.bitmapToScreenshotData(any(), any()) } returns
                ScreenshotData(data = "dGVzdA==", width = 700, height = 500)

            try {
                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_get_screen_state",
                            arguments = mapOf("include_screenshot" to true, "annotate" to false),
                        )
                    assertNotEquals(true, result.isError)
                }
                // The masked copy (not the raw captured bitmap) must be the one encoded, without annotation.
                verify(exactly = 1) {
                    deps.screenshotEncoder.bitmapToScreenshotData(masked, ScreenCaptureProvider.DEFAULT_QUALITY)
                }
                verify(exactly = 0) { deps.screenshotEncoder.bitmapToScreenshotData(resized, any()) }
                verify(exactly = 0) { deps.screenshotAnnotator.annotate(any(), any(), any(), any()) }
                verify(exactly = 1) { masked.recycle() }
                verify(exactly = 1) { resized.recycle() }
            } finally {
                unmockkConstructor(Canvas::class)
            }
        }

    private suspend fun screenStateText(client: Client): String {
        val result = client.callTool(name = "android_get_screen_state", arguments = emptyMap())
        return (result.content[0] as TextContent).text
    }

    private fun mockSurroundingText(text: String): SurroundingText {
        val mock = mockk<SurroundingText>()
        every { mock.text } returns text
        every { mock.offset } returns 0
        every { mock.selectionStart } returns text.length
        every { mock.selectionEnd } returns text.length
        return mock
    }
}
