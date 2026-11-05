package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("GetNodeDetailsTool")
class GetNodeDetailsToolTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockTreeParser: AccessibilityTreeParser
    private lateinit var mockElementFinder: ElementFinder
    private lateinit var mockRootNode: AccessibilityNodeInfo
    private lateinit var mockNodeCache: AccessibilityNodeCache
    private lateinit var mockWindowInfo: AccessibilityWindowInfo
    private lateinit var tool: GetNodeDetailsTool

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_a",
                        className = "android.widget.Button",
                        text = "Hello World",
                        contentDescription = "A button",
                        bounds = BoundsData(100, 200, 300, 260),
                    ),
                    AccessibilityNodeData(
                        id = "node_b",
                        className = "android.widget.TextView",
                        text = null,
                        contentDescription = null,
                        bounds = BoundsData(100, 300, 300, 360),
                    ),
                ),
        )

    private val sampleWindows =
        listOf(
            WindowData(
                windowId = 0,
                windowType = "APPLICATION",
                packageName = "com.example",
                title = "Test",
                activityName = ".Main",
                layer = 0,
                focused = true,
                tree = sampleTree,
            ),
        )

    @Suppress("LongMethod")
    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockTreeParser = mockk<AccessibilityTreeParser>()
        mockElementFinder = mockk<ElementFinder>()
        mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
        mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)

        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns "com.example"
        every {
            mockAccessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree

        tool =
            GetNodeDetailsTool(
                mockTreeParser,
                mockElementFinder,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("returns text and desc for found nodes")
    fun returnsTextAndDescForFoundNodes() =
        runTest {
            every { mockElementFinder.findNodeById(sampleWindows, "node_a") } returns sampleTree.children[0]
            every { mockElementFinder.findNodeById(sampleWindows, "node_b") } returns sampleTree.children[1]

            val params =
                buildJsonObject {
                    put(
                        "node_ids",
                        buildJsonArray {
                            add(JsonPrimitive("node_a"))
                            add(JsonPrimitive("node_b"))
                        },
                    )
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertEquals("node_id\ttext\tdesc\tpassword\tactions", lines[0])
            assertEquals("node_a\tHello World\tA button\tfalse\t-", lines[1])
            assertEquals("node_b\t-\t-\tfalse\t-", lines[2])
        }

    @Test
    @DisplayName("returns not_found for missing node IDs")
    fun returnsNotFoundForMissingNodeIds() =
        runTest {
            every { mockElementFinder.findNodeById(sampleWindows, "node_a") } returns sampleTree.children[0]
            every { mockElementFinder.findNodeById(sampleWindows, "node_missing") } returns null

            val params =
                buildJsonObject {
                    put(
                        "node_ids",
                        buildJsonArray {
                            add(JsonPrimitive("node_a"))
                            add(JsonPrimitive("node_missing"))
                        },
                    )
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertEquals("node_a\tHello World\tA button\tfalse\t-", lines[1])
            assertEquals("node_missing\tnot_found\tnot_found\tnot_found\tnot_found", lines[2])
        }

    @Test
    @DisplayName("returns dash for null text and desc")
    fun returnsDashForNullTextAndDesc() =
        runTest {
            every { mockElementFinder.findNodeById(sampleWindows, "node_b") } returns sampleTree.children[1]

            val params =
                buildJsonObject {
                    put("node_ids", buildJsonArray { add(JsonPrimitive("node_b")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertEquals("node_b\t-\t-\tfalse\t-", lines[1])
        }

    @Test
    @DisplayName("sanitizes tabs and newlines in text and desc")
    fun sanitizesTabsAndNewlinesInTextAndDesc() =
        runTest {
            val nodeWithSpecialChars =
                AccessibilityNodeData(
                    id = "node_special",
                    className = "android.widget.TextView",
                    text = "line1\tline2\nline3",
                    contentDescription = "desc\rwith\ttabs",
                    bounds = BoundsData(0, 0, 100, 100),
                )
            every { mockElementFinder.findNodeById(sampleWindows, "node_special") } returns nodeWithSpecialChars

            val params =
                buildJsonObject {
                    put("node_ids", buildJsonArray { add(JsonPrimitive("node_special")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertEquals("node_special\tline1 line2 line3\tdesc with tabs\tfalse\t-", lines[1])
        }

    @Test
    @DisplayName("reports true in the password column for a password field")
    fun reportsTrueInPasswordColumnForPasswordField() =
        runTest {
            val passwordNode =
                AccessibilityNodeData(
                    id = "node_pwd",
                    className = "android.widget.EditText",
                    bounds = BoundsData(0, 0, 100, 100),
                    editable = true,
                    isPassword = true,
                )
            every { mockElementFinder.findNodeById(sampleWindows, "node_pwd") } returns passwordNode

            val params =
                buildJsonObject {
                    put("node_ids", buildJsonArray { add(JsonPrimitive("node_pwd")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertEquals("node_pwd\t-\t-\ttrue\t-", lines[1])
        }

    @Test
    @DisplayName("lists the action labels in the actions column, redacted in the batch with each node's own slots")
    fun listsRedactedActionLabels() =
        runTest {
            val voteBar =
                AccessibilityNodeData(
                    id = "node_bar",
                    contentDescription = "349 votes",
                    bounds = BoundsData(0, 0, 100, 100),
                    actions = listOf("Upvote", "Downvote"),
                )
            val share =
                AccessibilityNodeData(
                    id = "node_share",
                    text = "Share",
                    bounds = BoundsData(0, 0, 100, 100),
                    actions = listOf("Share post"),
                )
            every { mockElementFinder.findNodeById(sampleWindows, "node_bar") } returns voteBar
            every { mockElementFinder.findNodeById(sampleWindows, "node_missing") } returns null
            every { mockElementFinder.findNodeById(sampleWindows, "node_b") } returns sampleTree.children[1]
            every { mockElementFinder.findNodeById(sampleWindows, "node_share") } returns share
            val gate = mockk<PrivacyToolGate>()
            coEvery { gate.nodeTexts(any(), any()) } answers {
                secondArg<List<Pair<String?, String>>>().map { (text, _) -> text?.let { "R($it)" } }
            }
            val redactingTool =
                GetNodeDetailsTool(
                    mockTreeParser,
                    mockElementFinder,
                    mockAccessibilityServiceProvider,
                    mockNodeCache,
                    gate,
                )

            val params =
                buildJsonObject {
                    put(
                        "node_ids",
                        buildJsonArray {
                            listOf("node_bar", "node_missing", "node_b", "node_share")
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            val result = redactingTool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertEquals("node_bar\t-\tR(349 votes)\tfalse\tR(Upvote)¦R(Downvote)", lines[1])
            assertEquals("node_missing\tnot_found\tnot_found\tnot_found\tnot_found", lines[2])
            assertEquals("node_b\t-\t-\tfalse\t-", lines[3])
            assertEquals("node_share\tR(Share)\t-\tfalse\tR(Share post)", lines[4])
        }

    @Test
    @DisplayName("does not truncate long text")
    fun doesNotTruncateLongText() =
        runTest {
            val longText = "a".repeat(200)
            val nodeWithLongText =
                AccessibilityNodeData(
                    id = "node_long",
                    className = "android.widget.TextView",
                    text = longText,
                    bounds = BoundsData(0, 0, 100, 100),
                )
            every { mockElementFinder.findNodeById(sampleWindows, "node_long") } returns nodeWithLongText

            val params =
                buildJsonObject {
                    put("node_ids", buildJsonArray { add(JsonPrimitive("node_long")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = stripUntrustedWarning(text).lines()
            assertTrue(lines[1].contains(longText))
        }

    @Test
    @DisplayName("throws InvalidParams when node_ids missing")
    fun throwsInvalidParamsWhenNodeIdsMissing() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(null)
            }
        }

    @Test
    @DisplayName("throws InvalidParams when node_ids is not array")
    fun throwsInvalidParamsWhenNodeIdsIsNotArray() =
        runTest {
            val params = buildJsonObject { put("node_ids", "not_an_array") }
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(params)
            }
        }

    @Test
    @DisplayName("throws InvalidParams when node_ids is empty array")
    fun throwsInvalidParamsWhenNodeIdsIsEmptyArray() =
        runTest {
            val params = buildJsonObject { put("node_ids", buildJsonArray { }) }
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(params)
            }
        }

    @Test
    @DisplayName("throws InvalidParams when node_ids contains non-string")
    fun throwsInvalidParamsWhenNodeIdsContainsNonString() =
        runTest {
            val params =
                buildJsonObject {
                    put("node_ids", buildJsonArray { add(JsonPrimitive(123)) })
                }
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(params)
            }
        }

    @Test
    @DisplayName("throws PermissionDenied when accessibility not available")
    fun throwsPermissionDeniedWhenAccessibilityNotAvailable() =
        runTest {
            every { mockAccessibilityServiceProvider.isReady() } returns false

            val params =
                buildJsonObject {
                    put("node_ids", buildJsonArray { add(JsonPrimitive("node_a")) })
                }
            assertThrows<McpToolException.PermissionDenied> {
                tool.execute(params)
            }
        }
}
