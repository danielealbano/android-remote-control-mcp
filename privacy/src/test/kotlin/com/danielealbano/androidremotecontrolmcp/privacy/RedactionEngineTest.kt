package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CardDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CredentialDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.EmailDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.IbanDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.NationalIdDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.PhoneDetector
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerEngine
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RedactionEngine")
class RedactionEngineTest {
    private val nerEngine = mockk<NerEngine>()
    private val engine =
        RedactionEngine(
            DeterministicEngine(
                CredentialDetector(),
                CardDetector(),
                IbanDetector(),
                EmailDetector(),
                PhoneDetector(),
                NationalIdDetector(),
            ),
            nerEngine,
            ContextExtractor(),
            Redactor(PseudonymStore()),
        )

    private val numbered = PrivacyModeConfig(enabled = true, placeholderFormat = PlaceholderFormat.NUMBERED)

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        editable: Boolean = false,
        bounds: BoundsData = BoundsData(0, 0, 10, 10),
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        id = id,
        text = text,
        contentDescription = desc,
        editable = editable,
        bounds = bounds,
        children = children,
    )

    private fun tree(root: AccessibilityNodeData) =
        MultiWindowResult(listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = root)))

    private fun allExcept(vararg keep: PiiCategory): Set<PiiCategory> = PiiCategory.entries.toSet() - keep.toSet()

    @Test
    fun `detect merges deterministic and model spans by priority`() =
        runTest {
            coEvery { nerEngine.detect(any()) } returns
                mapOf("0" to listOf(PiiDetection(PiiCategory.NAMES, 0, 5, PiiDetection.Source.MODEL)))

            val result =
                engine.redactTexts(listOf(TextItem("Sarah", DetectionContext(isPassword = true))), numbered)

            assertEquals("[CREDENTIAL_1]", result.first())
        }

    @Test
    fun `model detections merged and disabled category filtered`() =
        runTest {
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.EMAILS),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )
            coEvery { nerEngine.detect(any()) } returns
                mapOf("0" to listOf(PiiDetection(PiiCategory.NAMES, 0, 5, PiiDetection.Source.MODEL)))

            val result = engine.redactTexts(listOf(TextItem("Sarah a@b.com", DetectionContext.EMPTY)), config)

            assertTrue(result.first().startsWith("[NAME_1]"), "name should be redacted: $result")
            assertTrue(result.first().contains("a@b.com"), "disabled EMAILS should not be redacted: $result")
        }

    @Test
    fun `disabled category does not suppress overlapping enabled category`() =
        runTest {
            // Password field whose value is an email; CREDENTIALS is disabled but EMAILS stays on. The
            // whole-text structural credential span MUST NOT suppress the email span in the merge, or the
            // email would end up redacted by nobody and leak in the clear.
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories =
                        setOf(
                            PiiCategory.CREDENTIALS,
                            PiiCategory.NAMES,
                            PiiCategory.ADDRESSES,
                            PiiCategory.NATIONAL_IDS,
                        ),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )

            val result =
                engine.redactTexts(
                    listOf(TextItem("secret@example.com", DetectionContext(isPassword = true))),
                    config,
                )

            assertEquals("[EMAIL_1]", result.first())
            coVerify(exactly = 0) { nerEngine.detect(any()) }
        }

    @Test
    fun `redactTexts renders detections via redactor`() =
        runTest {
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )

            val result =
                engine.redactTexts(listOf(TextItem("mail a@b.com", DetectionContext.forField("email"))), config)

            assertEquals("mail [EMAIL_1]", result.first())
            coVerify(exactly = 0) { nerEngine.detect(any()) }
        }

    @Test
    fun `runModel skips blank items`() =
        runTest {
            coEvery { nerEngine.detect(any()) } returns emptyMap()

            val results =
                engine.detect(
                    listOf(TextItem("   ", DetectionContext.EMPTY), TextItem("Sarah", DetectionContext.EMPTY)),
                    numbered,
                )

            assertEquals(2, results.size)
            assertTrue(results[0].isEmpty())
            coVerify(exactly = 1) {
                nerEngine.detect(match { segments -> segments.size == 1 && segments.first().text == "Sarah" })
            }
        }

    @Test
    fun `redactTree redacts node text and returns flagged bounds`() =
        runTest {
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = allExcept(PiiCategory.EMAILS),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )
            val root =
                node(
                    "root",
                    children =
                        listOf(
                            node("n1", text = "a@b.com", desc = "mail c@d.com"),
                            node("n2", text = "hello world"),
                        ),
                )

            val processed = engine.redactTree(tree(root), config)

            assertEquals(1, processed.flaggedBounds.size)
            coVerify(exactly = 0) { nerEngine.detect(any()) }
        }

    @Test
    fun `redactTree redacts action labels in order and flags the node`() =
        runTest {
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = allExcept(PiiCategory.EMAILS),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )
            val barBounds = BoundsData(0, 100, 50, 150)
            val root =
                node(
                    "root",
                    children =
                        listOf(
                            node("bar", bounds = barBounds)
                                .copy(actions = listOf("Mail a@b.com", "Upvote", "Mail c@d.com")),
                            node("clean", text = "hello").copy(actions = listOf("Share post")),
                        ),
                )

            val processed = engine.redactTree(tree(root), config)

            val children = processed.result.windows.single().tree.children
            assertEquals(listOf("Mail [EMAIL_1]", "Upvote", "Mail [EMAIL_2]"), children[0].actions)
            assertEquals(listOf("Share post"), children[1].actions)
            assertEquals(listOf(barBounds), processed.flaggedBounds)
        }

    @Test
    fun `redactTree uses nearest label context for editable fields`() =
        runTest {
            // NationalIdDetector only fires with a national-id cue in the context; the cue arrives via the
            // geometric nearest label ("SSN") above the editable value node.
            coEvery { nerEngine.detect(any()) } returns emptyMap()
            val root =
                node(
                    "root",
                    children =
                        listOf(
                            node("label", text = "SSN", bounds = BoundsData(0, 0, 100, 40)),
                            node(
                                "value",
                                text = "078-05-1120",
                                editable = true,
                                bounds = BoundsData(0, 50, 100, 130),
                            ),
                        ),
                )

            val processed = engine.redactTree(tree(root), numbered)

            val redactedValue =
                processed.result.windows
                    .first()
                    .tree.children[1]
                    .text
            assertEquals("[ID_1]", redactedValue)
        }

    @Test
    fun `redactTree packs all segments in one detect call`() =
        runTest {
            coEvery { nerEngine.detect(any()) } returns emptyMap()
            val root =
                node(
                    "root",
                    children = listOf(node("n1", text = "one"), node("n2", text = "two"), node("n3", text = "three")),
                )

            engine.redactTree(tree(root), numbered)

            coVerify(exactly = 1) { nerEngine.detect(any()) }
        }

    @Test
    fun `model detections propagate PrivacyModelException`() =
        runTest {
            coEvery { nerEngine.detect(any()) } throws PrivacyModelException("boom")

            var thrown: Throwable? = null
            try {
                engine.detect(listOf(TextItem("Sarah", DetectionContext.EMPTY)), numbered)
            } catch (e: PrivacyModelException) {
                thrown = e
            }
            assertTrue(thrown is PrivacyModelException)
        }
}
