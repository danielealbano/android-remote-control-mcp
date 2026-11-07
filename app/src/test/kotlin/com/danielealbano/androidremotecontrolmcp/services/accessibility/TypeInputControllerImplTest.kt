package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.text.InputType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TypeInputControllerImpl")
class TypeInputControllerImplTest {
    @Test
    fun `isPasswordInputType is true for every text password variation`() {
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test
    fun `isPasswordInputType is true for a number password`() {
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun `isPasswordInputType ignores the input type flags`() {
        assertTrue(
            isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            ),
        )
    }

    @Test
    fun `isPasswordInputType is false for other inputs`() {
        assertFalse(isPasswordInputType(InputType.TYPE_NULL))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun `isPasswordInputType reads the variation of its own class only`() {
        // The text password variation under the number class is not a number password.
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }
}
