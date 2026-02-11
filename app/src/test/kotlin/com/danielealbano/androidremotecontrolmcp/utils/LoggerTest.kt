package com.danielealbano.androidremotecontrolmcp.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Logger")
class LoggerTest {
    @Nested
    @DisplayName("sanitize")
    inner class Sanitize {
        @Test
        fun `replaces UUID with REDACTED`() {
            val message = "Token: 550e8400-e29b-41d4-a716-446655440000"

            val result = Logger.sanitize(message)

            assertEquals("Token: [REDACTED]", result)
        }

        @Test
        fun `replaces multiple UUIDs`() {
            val message =
                "Old: 550e8400-e29b-41d4-a716-446655440000, " +
                    "New: 6ba7b810-9dad-11d1-80b4-00c04fd430c8"

            val result = Logger.sanitize(message)

            assertEquals("Old: [REDACTED], New: [REDACTED]", result)
        }

        @Test
        fun `leaves non-UUID strings untouched`() {
            val message = "Server started on port 8080"

            val result = Logger.sanitize(message)

            assertEquals("Server started on port 8080", result)
        }

        @Test
        fun `handles empty string`() {
            val result = Logger.sanitize("")

            assertEquals("", result)
        }

        @Test
        fun `leaves partial UUID untouched`() {
            val message = "Value: 550e8400-e29b-41d4"

            val result = Logger.sanitize(message)

            assertEquals("Value: 550e8400-e29b-41d4", result)
        }

        @Test
        fun `replaces uppercase UUID`() {
            val message = "Token: 550E8400-E29B-41D4-A716-446655440000"

            val result = Logger.sanitize(message)

            assertEquals("Token: [REDACTED]", result)
        }
    }
}
