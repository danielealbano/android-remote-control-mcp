package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BuiltinStorageLocation")
class BuiltinStorageLocationTest {
    @Nested
    @DisplayName("fromLocationId")
    inner class FromLocationIdTest {
        @Test
        fun `fromLocationId returns correct entry for each builtin`() {
            assertEquals(
                BuiltinStorageLocation.DOWNLOADS,
                BuiltinStorageLocation.fromLocationId("builtin:downloads"),
            )
            assertEquals(
                BuiltinStorageLocation.PICTURES,
                BuiltinStorageLocation.fromLocationId("builtin:pictures"),
            )
            assertEquals(
                BuiltinStorageLocation.MOVIES,
                BuiltinStorageLocation.fromLocationId("builtin:movies"),
            )
            assertEquals(
                BuiltinStorageLocation.MUSIC,
                BuiltinStorageLocation.fromLocationId("builtin:music"),
            )
        }

        @Test
        fun `fromLocationId returns null for unknown ID`() {
            assertNull(BuiltinStorageLocation.fromLocationId("builtin:documents"))
        }
    }

    @Nested
    @DisplayName("isBuiltinId")
    inner class IsBuiltinIdTest {
        @Test
        fun `isBuiltinId returns true for builtin prefix`() {
            assertTrue(BuiltinStorageLocation.isBuiltinId("builtin:downloads"))
        }

        @Test
        fun `isBuiltinId returns false for non-builtin ID`() {
            assertFalse(BuiltinStorageLocation.isBuiltinId("saf:some-uri"))
        }
    }

    @Nested
    @DisplayName("entries")
    inner class EntriesTest {
        @Test
        fun `all entries have unique locationIds`() {
            val locationIds = BuiltinStorageLocation.entries.map { it.locationId }
            assertEquals(locationIds.size, locationIds.toSet().size)
        }
    }

    @Nested
    @DisplayName("readMediaPermission")
    inner class ReadMediaPermissionTest {
        @Test
        fun `downloads has no readMediaPermission`() {
            assertNull(BuiltinStorageLocation.DOWNLOADS.readMediaPermission)
        }

        @Test
        fun `pictures movies music have readMediaPermission`() {
            assertNotNull(BuiltinStorageLocation.PICTURES.readMediaPermission)
            assertNotNull(BuiltinStorageLocation.MOVIES.readMediaPermission)
            assertNotNull(BuiltinStorageLocation.MUSIC.readMediaPermission)
        }
    }

    @Nested
    @DisplayName("validatePath")
    inner class ValidatePathTest {
        @Test
        fun `validatePath accepts valid relative path`() {
            BuiltinStorageLocation.validatePath("subdir/file.txt")
        }

        @Test
        fun `validatePath accepts empty path`() {
            BuiltinStorageLocation.validatePath("")
        }

        @Test
        fun `validatePath rejects double-dot segments`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("../secret")
            }
        }

        @Test
        fun `validatePath rejects single-dot segments`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("./file")
            }
        }

        @Test
        fun `validatePath rejects absolute paths`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("/etc/passwd")
            }
        }

        @Test
        fun `validatePath rejects control characters`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("file\nname")
            }
        }

        @Test
        fun `validatePath rejects nested traversal`() {
            assertThrows(McpToolException.InvalidParams::class.java) {
                BuiltinStorageLocation.validatePath("subdir/../../etc")
            }
        }
    }
}
