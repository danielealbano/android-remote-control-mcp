package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PrivacyToolGate")
class PrivacyToolGateTest {
    private val pipeline = mockk<PrivacyPipeline>()
    private val gate = PrivacyToolGate(pipeline, ContextExtractor())
    private val sentItems = slot<List<TextItem>>()

    init {
        // Echoes each text with a marker so the tests can check which slot got which result.
        coEvery { pipeline.processTexts(capture(sentItems)) } answers {
            firstArg<List<TextItem>>().map { "redacted:${it.text}" }
        }
    }

    private val passwordField =
        AccessibilityNodeData(
            id = "node_pwd",
            className = "android.widget.EditText",
            resourceId = "com.example:id/login_secret",
            hintText = "Your passphrase",
            text = "hunter2",
            bounds = BoundsData(100, 400, 900, 480),
            editable = true,
            isPassword = true,
        )

    private val label =
        AccessibilityNodeData(
            id = "node_label",
            className = "android.widget.TextView",
            text = "API token",
            bounds = BoundsData(100, 500, 900, 560),
        )

    private val labeledField =
        AccessibilityNodeData(
            id = "node_api",
            className = "android.widget.EditText",
            text = "sk-123",
            bounds = BoundsData(100, 580, 900, 660),
            editable = true,
        )

    private val appWindow =
        WindowData(
            windowId = 1,
            windowType = "APPLICATION",
            tree =
                AccessibilityNodeData(
                    id = "node_root",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    children = listOf(passwordField, label, labeledField),
                ),
        )

    @Test
    fun `nodeTexts gives each text the detection context of its node`() =
        runTest {
            val result =
                gate.nodeTexts(
                    listOf(appWindow),
                    listOf("hunter2" to "node_pwd", "sk-123" to "node_api"),
                )

            assertEquals(listOf("redacted:hunter2", "redacted:sk-123"), result)
            val (pwdItem, apiItem) = sentItems.captured
            assertTrue(pwdItem.context.isPassword)
            assertTrue(pwdItem.context.isEditable)
            assertEquals("Your passphrase", pwdItem.context.hintText)
            assertEquals(listOf("login", "secret"), pwdItem.context.resourceIdWords)
            assertFalse(apiItem.context.isPassword)
            assertTrue(apiItem.context.isEditable)
            // No labeledBy association: the nearest text above the field is its label, as in get_screen_state.
            assertEquals("API token", apiItem.context.labelText)
        }

    @Test
    fun `nodeTexts finds the node in whichever window holds it`() =
        runTest {
            val dialog =
                WindowData(
                    windowId = 7,
                    windowType = "SYSTEM",
                    tree = passwordField.copy(id = "node_dialog_pwd"),
                )

            gate.nodeTexts(listOf(appWindow, dialog), listOf("hunter2" to "node_dialog_pwd"))

            val item = sentItems.captured.single()
            assertTrue(item.context.isPassword)
        }

    @Test
    fun `nodeTexts gives an empty context to a node id that is not in the windows`() =
        runTest {
            gate.nodeTexts(listOf(appWindow), listOf("hunter2" to "node_gone"))

            assertEquals(DetectionContext.EMPTY, sentItems.captured.single().context)
        }

    @Test
    fun `nodeTexts keeps null texts in their slot`() =
        runTest {
            val result =
                gate.nodeTexts(
                    listOf(appWindow),
                    listOf(null to "node_pwd", "sk-123" to "node_api", null to "node_api"),
                )

            assertEquals(listOf(null, "redacted:sk-123", null), result)
            assertEquals(listOf("sk-123"), sentItems.captured.map { it.text })
        }

    @Test
    fun `nodeTexts does not call the pipeline when every text is null`() =
        runTest {
            val result = gate.nodeTexts(listOf(appWindow), listOf(null to "node_pwd", null to "node_api"))

            assertEquals(listOf(null, null), result)
            coVerify(exactly = 0) { pipeline.processTexts(any()) }
        }

    @Test
    fun `nodeText redacts one text with its node context`() =
        runTest {
            val result = gate.nodeText(listOf(appWindow), "hunter2", "node_pwd")

            assertEquals("redacted:hunter2", result)
            val item = sentItems.captured.single()
            assertTrue(item.context.isPassword)
        }

    @Test
    fun `texts keeps using the field name as the only context`() =
        runTest {
            val result = gate.texts(listOf("a@b.com" to "clipboard", null to "clipboard"))

            assertEquals(listOf("redacted:a@b.com", null), result)
            assertEquals(DetectionContext.forField("clipboard"), sentItems.captured.single().context)
        }
}
