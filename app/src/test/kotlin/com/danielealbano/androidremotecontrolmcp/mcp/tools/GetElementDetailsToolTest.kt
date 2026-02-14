package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
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

@DisplayName("GetElementDetailsTool")
class GetElementDetailsToolTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockTreeParser: AccessibilityTreeParser
    private lateinit var mockElementFinder: ElementFinder
    private lateinit var mockRootNode: AccessibilityNodeInfo
    private lateinit var tool: GetElementDetailsTool

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

    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockTreeParser = mockk<AccessibilityTreeParser>()
        mockElementFinder = mockk<ElementFinder>()
        mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode
        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree

        tool = GetElementDetailsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("returns text and desc for found elements")
    fun returnsTextAndDescForFoundElements() =
        runTest {
            every { mockElementFinder.findNodeById(sampleTree, "node_a") } returns sampleTree.children[0]
            every { mockElementFinder.findNodeById(sampleTree, "node_b") } returns sampleTree.children[1]

            val params =
                buildJsonObject {
                    put(
                        "ids",
                        buildJsonArray {
                            add(JsonPrimitive("node_a"))
                            add(JsonPrimitive("node_b"))
                        },
                    )
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = text.lines()
            assertEquals("id\ttext\tdesc", lines[0])
            assertEquals("node_a\tHello World\tA button", lines[1])
            assertEquals("node_b\t-\t-", lines[2])
        }

    @Test
    @DisplayName("returns not_found for missing element IDs")
    fun returnsNotFoundForMissingElementIds() =
        runTest {
            every { mockElementFinder.findNodeById(sampleTree, "node_a") } returns sampleTree.children[0]
            every { mockElementFinder.findNodeById(sampleTree, "node_missing") } returns null

            val params =
                buildJsonObject {
                    put(
                        "ids",
                        buildJsonArray {
                            add(JsonPrimitive("node_a"))
                            add(JsonPrimitive("node_missing"))
                        },
                    )
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = text.lines()
            assertEquals("node_a\tHello World\tA button", lines[1])
            assertEquals("node_missing\tnot_found\tnot_found", lines[2])
        }

    @Test
    @DisplayName("returns dash for null text and desc")
    fun returnsDashForNullTextAndDesc() =
        runTest {
            every { mockElementFinder.findNodeById(sampleTree, "node_b") } returns sampleTree.children[1]

            val params =
                buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive("node_b")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = text.lines()
            assertEquals("node_b\t-\t-", lines[1])
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
            every { mockElementFinder.findNodeById(sampleTree, "node_special") } returns nodeWithSpecialChars

            val params =
                buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive("node_special")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = text.lines()
            assertEquals("node_special\tline1 line2 line3\tdesc with tabs", lines[1])
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
            every { mockElementFinder.findNodeById(sampleTree, "node_long") } returns nodeWithLongText

            val params =
                buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive("node_long")) })
                }
            val result = tool.execute(params)
            val text = (result.content[0] as TextContent).text

            val lines = text.lines()
            assertTrue(lines[1].contains(longText))
        }

    @Test
    @DisplayName("throws InvalidParams when ids missing")
    fun throwsInvalidParamsWhenIdsMissing() =
        runTest {
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(null)
            }
        }

    @Test
    @DisplayName("throws InvalidParams when ids is not array")
    fun throwsInvalidParamsWhenIdsIsNotArray() =
        runTest {
            val params = buildJsonObject { put("ids", "not_an_array") }
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(params)
            }
        }

    @Test
    @DisplayName("throws InvalidParams when ids is empty array")
    fun throwsInvalidParamsWhenIdsIsEmptyArray() =
        runTest {
            val params = buildJsonObject { put("ids", buildJsonArray { }) }
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(params)
            }
        }

    @Test
    @DisplayName("throws InvalidParams when ids contains non-string")
    fun throwsInvalidParamsWhenIdsContainsNonString() =
        runTest {
            val params =
                buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive(123)) })
                }
            assertThrows<McpToolException.InvalidParams> {
                tool.execute(params)
            }
        }

    @Test
    @DisplayName("throws PermissionDenied when accessibility not available")
    fun throwsPermissionDeniedWhenAccessibilityNotAvailable() =
        runTest {
            every { mockAccessibilityServiceProvider.getRootNode() } returns null

            val params =
                buildJsonObject {
                    put("ids", buildJsonArray { add(JsonPrimitive("node_a")) })
                }
            assertThrows<McpToolException.PermissionDenied> {
                tool.execute(params)
            }
        }
}
