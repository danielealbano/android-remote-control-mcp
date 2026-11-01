package com.danielealbano.androidremotecontrolmcp.testutil

import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.privacy.ProcessedTree
import com.danielealbano.androidremotecontrolmcp.privacy.PseudonymStore
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotRedactor
import io.mockk.coEvery
import io.mockk.mockk

/**
 * Passthrough Privacy Mode collaborators for tool unit tests that do not exercise redaction.
 * The gate returns every field unchanged, the substitutor is identity (empty store), and the
 * screenshot redactor is the real no-op-when-unflagged implementation.
 */
object PrivacyToolTestDoubles {
    fun passthroughGate(): PrivacyToolGate {
        val gate = mockk<PrivacyToolGate>()
        coEvery { gate.text(any(), any()) } answers { firstArg<String?>() }
        coEvery { gate.texts(any()) } answers {
            firstArg<List<Pair<String?, String>>>().map { it.first }
        }
        coEvery { gate.nodeTexts(any(), any()) } answers {
            secondArg<List<Pair<String?, String>>>().map { it.first }
        }
        coEvery { gate.nodeText(any(), any(), any()) } answers { secondArg<String?>() }
        coEvery { gate.tree(any()) } answers { ProcessedTree(firstArg<MultiWindowResult>(), emptyList()) }
        return gate
    }

    fun identitySubstitutor(): PlaceholderSubstitutor = PlaceholderSubstitutor(PseudonymStore())

    fun screenshotRedactor(): ScreenshotRedactor = ScreenshotRedactor()
}
