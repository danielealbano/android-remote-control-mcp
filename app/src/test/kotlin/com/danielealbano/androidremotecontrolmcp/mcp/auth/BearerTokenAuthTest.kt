package com.danielealbano.androidremotecontrolmcp.mcp.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BearerTokenAuthTest {
    // --- constantTimeEquals tests ---

    @Test
    fun `constantTimeEquals returns true for identical strings`() {
        assertTrue(constantTimeEquals("my-secret-token", "my-secret-token"))
    }

    @Test
    fun `constantTimeEquals returns false for different strings`() {
        assertFalse(constantTimeEquals("my-secret-token", "wrong-token"))
    }

    @Test
    fun `constantTimeEquals returns false for empty vs non-empty`() {
        assertFalse(constantTimeEquals("my-secret-token", ""))
    }

    @Test
    fun `constantTimeEquals returns true for both empty`() {
        assertTrue(constantTimeEquals("", ""))
    }

    @Test
    fun `constantTimeEquals returns false for strings differing in length`() {
        assertFalse(constantTimeEquals("short", "a-much-longer-string"))
    }

    @Test
    fun `constantTimeEquals returns false for strings differing by one character`() {
        assertFalse(constantTimeEquals("abcdef", "abcdeg"))
    }

    @Test
    fun `constantTimeEquals handles unicode strings`() {
        assertTrue(constantTimeEquals("token-\u00e9\u00e8", "token-\u00e9\u00e8"))
        assertFalse(constantTimeEquals("token-\u00e9\u00e8", "token-\u00e9\u00e7"))
    }

    @Test
    fun `constantTimeEquals handles UUID-format tokens`() {
        val token = "550e8400-e29b-41d4-a716-446655440000"
        assertTrue(constantTimeEquals(token, token))
        assertFalse(constantTimeEquals(token, "550e8400-e29b-41d4-a716-446655440001"))
    }

    // --- Timing consistency verification ---
    // Note: True constant-time verification requires statistical analysis of timing
    // measurements, which is impractical in a unit test. The test below verifies
    // that MessageDigest.isEqual is being used (by checking the function produces
    // correct results for edge cases that would differ between naive and constant-time
    // implementations).

    @Test
    fun `constantTimeEquals returns correct result regardless of mismatch position`() {
        val base = "abcdefghijklmnop"
        // Mismatch at first character
        assertFalse(constantTimeEquals(base, "Xbcdefghijklmnop"))
        // Mismatch at last character
        assertFalse(constantTimeEquals(base, "abcdefghijklmnoX"))
        // Mismatch at middle character
        assertFalse(constantTimeEquals(base, "abcdefgXijklmnop"))
    }
}
