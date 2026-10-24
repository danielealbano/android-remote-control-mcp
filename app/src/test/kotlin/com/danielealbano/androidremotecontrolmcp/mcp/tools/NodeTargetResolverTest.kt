package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("NodeTargetResolver")
class NodeTargetResolverTest {
    private val resolver =
        NodeTargetResolver(
            ElementFinder(),
            PrivacyToolTestDoubles.passthroughGate(),
            PrivacyToolTestDoubles.identitySubstitutor(),
        )

    private fun node(
        id: String,
        text: String? = null,
        visible: Boolean = true,
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        id = id,
        text = text,
        bounds = BoundsData(0, 0, 10, 10),
        visible = visible,
        children = children,
    )

    /** Two on-screen "OK" buttons, one off-screen "OK", one on-screen "Cancel". */
    private val windows =
        listOf(
            WindowData(
                windowId = 0,
                windowType = "APPLICATION",
                tree =
                    node(
                        "node_root",
                        children =
                            listOf(
                                node("node_ok_1", text = "OK"),
                                node("node_ok_2", text = "OK"),
                                node("node_ok_off", text = "OK", visible = false),
                                node("node_cancel", text = "Cancel"),
                                node("node_only_off", text = "Hidden", visible = false),
                            ),
                    ),
            ),
        )

    private fun selector(
        value: String,
        by: String = "text",
        exactMatch: Boolean = true,
    ) = NodeTarget.BySelector(NodeSelector(FindBy.TEXT, by, value, exactMatch))

    @Nested
    @DisplayName("parse")
    inner class Parse {
        @Test
        fun `node_id alone is a target by id`() {
            assertEquals(NodeTarget.ById("node_x"), resolver.parse(buildJsonObject { put("node_id", "node_x") }))
        }

        @Test
        fun `by and value are a selector, exact_match defaults to false`() {
            val target =
                resolver.parse(
                    buildJsonObject {
                        put("by", "content_desc")
                        put("value", "Save")
                    },
                ) as NodeTarget.BySelector

            assertEquals(FindBy.CONTENT_DESC, target.selector.findBy)
            assertEquals("Save", target.selector.value)
            assertFalse(target.selector.exactMatch)
        }

        @Test
        fun `exact_match true is honoured and a JSON null exact_match counts as absent`() {
            val exact =
                resolver.parse(
                    buildJsonObject {
                        put("by", "text")
                        put("value", "OK")
                        put("exact_match", true)
                    },
                ) as NodeTarget.BySelector
            val nullExact =
                resolver.parse(
                    buildJsonObject {
                        put("by", "text")
                        put("value", "OK")
                        put("exact_match", JsonNull)
                    },
                ) as NodeTarget.BySelector

            assertTrue(exact.selector.exactMatch)
            assertFalse(nullExact.selector.exactMatch)
        }

        @Test
        fun `a node_id with JSON null selector fields is a target by id`() {
            val target =
                resolver.parse(
                    buildJsonObject {
                        put("node_id", "node_x")
                        put("by", JsonNull)
                        put("value", JsonNull)
                    },
                )

            assertEquals(NodeTarget.ById("node_x"), target)
        }

        @Test
        fun `a JSON null node_id counts as absent`() {
            val target =
                resolver.parse(
                    buildJsonObject {
                        put("node_id", JsonNull)
                        put("by", "text")
                        put("value", "OK")
                    },
                )

            assertTrue(target is NodeTarget.BySelector)
        }

        @Test
        fun `invalid combinations and values are rejected`() {
            val invalid =
                listOf(
                    buildJsonObject {},
                    buildJsonObject { put("node_id", "") },
                    buildJsonObject {
                        put("node_id", "node_x")
                        put("by", "text")
                        put("value", "OK")
                    },
                    buildJsonObject { put("by", "text") },
                    buildJsonObject { put("value", "OK") },
                    buildJsonObject {
                        put("by", "text")
                        put("value", "")
                    },
                    buildJsonObject {
                        put("by", "colour")
                        put("value", "red")
                    },
                    buildJsonObject {
                        put("by", "text")
                        put("value", "OK")
                        put("exact_match", "yes")
                    },
                    buildJsonObject { put("node_id", 7) },
                    buildJsonObject {
                        put("node_id", "node_x")
                        put("exact_match", "yes")
                    },
                    buildJsonObject { putJsonObject("node_id") { put("id", "node_x") } },
                )
            for (arguments in invalid) {
                assertThrows<McpToolException.InvalidParams>("expected rejection of $arguments") {
                    resolver.parse(arguments)
                }
            }
        }

        @Test
        fun `a selector value is matched with pseudonym placeholders reversed`() {
            val substitutor = mockk<PlaceholderSubstitutor>()
            every { substitutor.substitute(any()) } answers {
                if (firstArg<String>() == "NAME#1") "John" else firstArg()
            }
            val withPseudonyms =
                NodeTargetResolver(ElementFinder(), PrivacyToolTestDoubles.passthroughGate(), substitutor)

            val target =
                withPseudonyms.parse(
                    buildJsonObject {
                        put("by", "text")
                        put("value", "NAME#1")
                    },
                ) as NodeTarget.BySelector

            assertEquals("John", target.selector.value)
            assertFalse(target.selector.toString().contains("John"))
        }
    }

    @Nested
    @DisplayName("resolve")
    inner class Resolve {
        @Test
        fun `a node id resolves to itself without looking it up`() =
            runTest {
                assertEquals(
                    NodeResolution.Resolved("node_missing"),
                    resolver.resolve(NodeTarget.ById("node_missing"), windows),
                )
            }

        @Test
        fun `a single on-screen match resolves to it`() =
            runTest {
                assertEquals(NodeResolution.Resolved("node_cancel"), resolver.resolve(selector("Cancel"), windows))
            }

        @Test
        fun `off-screen matches are ignored for on-screen resolution`() =
            runTest {
                assertThrows<McpToolException.NodeNotFound> { resolver.resolve(selector("Hidden"), windows) }
            }

        @Test
        fun `off-screen matches count when on-screen only is off`() =
            runTest {
                assertEquals(
                    NodeResolution.Resolved("node_only_off"),
                    resolver.resolve(selector("Hidden"), windows, onScreenOnly = false),
                )
            }

        @Test
        fun `no match is node not found`() =
            runTest {
                assertThrows<McpToolException.NodeNotFound> { resolver.resolve(selector("Nope"), windows) }
            }

        @Test
        fun `two on-screen matches are an untrusted error listing the candidates`() =
            runTest {
                val resolution = resolver.resolve(selector("OK"), windows) as NodeResolution.Ambiguous

                val text = (resolution.result.content.single() as TextContent).text
                assertEquals(true, resolution.result.isError)
                assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.lines().first())
                assertTrue(text.contains("2 on-screen nodes match by=text"), text)
                assertTrue(text.contains("node_ok_1") && text.contains("node_ok_2"), text)
                assertFalse(text.contains("node_ok_off"), text)
            }

        @Test
        fun `ambiguous candidates are capped and redacted by Privacy Mode`() =
            runTest {
                val many =
                    listOf(
                        WindowData(
                            windowId = 0,
                            windowType = "APPLICATION",
                            tree = node("node_root", children = (1..8).map { node("node_item_$it", text = "Item") }),
                        ),
                    )
                val redactingGate = mockk<PrivacyToolGate>()
                coEvery { redactingGate.texts(any()) } answers {
                    firstArg<List<Pair<String?, String>>>().map { pair -> pair.first?.let { "[REDACTED]" } }
                }
                val redacting =
                    NodeTargetResolver(ElementFinder(), redactingGate, PrivacyToolTestDoubles.identitySubstitutor())

                val resolution = redacting.resolve(selector("Item"), many) as NodeResolution.Ambiguous

                val text = (resolution.result.content.single() as TextContent).text
                assertTrue(text.contains("8 on-screen nodes match"), text)
                assertTrue(text.contains("node_item_5") && !text.contains("node_item_6"), text)
                assertFalse(text.contains("\"Item\""), text)
                assertTrue(text.contains("[REDACTED]"), text)
            }

        @Test
        fun `matches in different windows are ambiguous together`() =
            runTest {
                val twoWindows =
                    listOf(
                        WindowData(windowId = 0, windowType = "APPLICATION", tree = node("node_app_ok", text = "OK")),
                        WindowData(windowId = 7, windowType = "SYSTEM", tree = node("node_dialog_ok", text = "OK")),
                    )

                val resolution = resolver.resolve(selector("OK"), twoWindows) as NodeResolution.Ambiguous

                val text = (resolution.result.content.single() as TextContent).text
                assertTrue(text.contains("node_app_ok") && text.contains("node_dialog_ok"), text)
            }

        @Test
        fun `long candidate texts are shortened like in get_screen_state`() =
            runTest {
                val long = "x".repeat(CompactTreeFormatter.MAX_TEXT_LENGTH + 50)
                val withLongText =
                    listOf(
                        WindowData(
                            windowId = 0,
                            windowType = "APPLICATION",
                            tree = node("node_root", children = listOf(node("node_1", long), node("node_2", long))),
                        ),
                    )

                val resolution = resolver.resolve(selector("x", exactMatch = false), withLongText)

                val text = ((resolution as NodeResolution.Ambiguous).result.content.single() as TextContent).text
                val shortened =
                    "x".repeat(CompactTreeFormatter.MAX_TEXT_LENGTH) + CompactTreeFormatter.TRUNCATION_SUFFIX
                assertTrue(text.contains("\"$shortened\""), text)
                assertFalse(text.contains(long), text)
            }
    }
}
