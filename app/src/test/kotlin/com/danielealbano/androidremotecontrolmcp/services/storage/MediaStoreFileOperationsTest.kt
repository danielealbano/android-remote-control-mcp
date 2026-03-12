@file:Suppress("TooManyFunctions", "LargeClass")

package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@ExtendWith(MockKExtension::class)
@DisplayName("MediaStoreFileOperationsImpl")
class MediaStoreFileOperationsTest {
    @MockK(relaxed = true)
    private lateinit var mockContext: Context

    @MockK(relaxed = true)
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockStorageLocationProvider: StorageLocationProvider

    @MockK
    private lateinit var mockSettingsRepository: SettingsRepository

    private lateinit var operations: MediaStoreFileOperationsImpl

    private val fakeCollectionUri: Uri = mockk(relaxed = true)
    private val fakeFileUri: Uri = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { Uri.withAppendedPath(any(), any()) } returns fakeFileUri

        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.packageName } returns "com.test.app"

        coEvery { mockStorageLocationProvider.isAllFilesMode(any()) } returns false
        coEvery { mockStorageLocationProvider.isWriteAllowed(any()) } returns true
        coEvery { mockStorageLocationProvider.isDeleteAllowed(any()) } returns true
        coEvery { mockSettingsRepository.getServerConfig() } returns ServerConfig()

        mockkObject(BuiltinStorageLocation.DOWNLOADS)
        every { BuiltinStorageLocation.DOWNLOADS.collectionUri } returns fakeCollectionUri

        operations = MediaStoreFileOperationsImpl(mockContext, mockStorageLocationProvider, mockSettingsRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(BuiltinStorageLocation.DOWNLOADS)
        unmockkStatic(Log::class)
        unmockkStatic(Uri::class)
    }

    private fun createMockCursor(rows: List<Map<String, Any?>>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var position = -1

        every { cursor.moveToNext() } answers {
            position++
            position < rows.size
        }
        every { cursor.moveToFirst() } answers {
            position = 0
            rows.isNotEmpty()
        }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            val colName = firstArg<String>()
            when (colName) {
                MediaStore.MediaColumns._ID -> 0
                MediaStore.MediaColumns.DISPLAY_NAME -> 1
                MediaStore.MediaColumns.RELATIVE_PATH -> 2
                MediaStore.MediaColumns.SIZE -> 3
                MediaStore.MediaColumns.DATE_MODIFIED -> 4
                MediaStore.MediaColumns.MIME_TYPE -> 5
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> 6
                else -> throw IllegalArgumentException("Unknown column: $colName")
            }
        }
        every { cursor.getLong(0) } answers { (rows.getOrNull(position)?.get("id") as? Long) ?: 0L }
        every { cursor.getString(1) } answers { rows.getOrNull(position)?.get("name") as? String }
        every { cursor.getString(2) } answers { rows.getOrNull(position)?.get("relPath") as? String }
        every { cursor.getLong(3) } answers { (rows.getOrNull(position)?.get("size") as? Long) ?: 0L }
        every { cursor.getLong(4) } answers { (rows.getOrNull(position)?.get("dateModified") as? Long) ?: 0L }
        every { cursor.getString(5) } answers { rows.getOrNull(position)?.get("mimeType") as? String }
        every { cursor.getString(6) } answers { rows.getOrNull(position)?.get("owner") as? String }

        return cursor
    }

    private fun stubQueryReturning(cursor: Cursor) {
        every {
            mockContentResolver.query(any(), any(), any(), any(), any())
        } returns cursor
    }

    private fun stubFindOwnedFile(
        id: Long = 1L,
        found: Boolean = true,
    ) {
        if (found) {
            val findCursor = createMockCursor(listOf(mapOf("id" to id)))
            every {
                mockContentResolver.query(any(), any(), any(), any(), any())
            } returns findCursor
        } else {
            val emptyCursor = createMockCursor(emptyList())
            every {
                mockContentResolver.query(any(), any(), any(), any(), any())
            } returns emptyCursor
        }
    }

    private fun stubFileSizeQuery(size: Long = 100L) {
        val sizeCursor = createMockCursor(listOf(mapOf("size" to size)))
        every {
            mockContentResolver.query(eq(fakeFileUri), any(), any(), any(), any())
        } returns sizeCursor
    }

    // ─── listFiles ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("listFiles")
    inner class ListFiles {
        @Test
        fun `listFiles returns files at root with empty path`() =
            runTest {
                val rows =
                    listOf(
                        mapOf(
                            "id" to 1L,
                            "name" to "notes.txt",
                            "relPath" to "Download/",
                            "size" to 1024L,
                            "dateModified" to 1700000000L,
                            "mimeType" to "text/plain",
                        ),
                    )
                stubQueryReturning(createMockCursor(rows))

                val result = operations.listFiles("builtin:downloads", "", 0, 50)

                assertEquals(1, result.totalCount)
                assertEquals("notes.txt", result.files[0].name)
                assertFalse(result.files[0].isDirectory)
                assertEquals(1024L, result.files[0].size)
            }

        @Test
        fun `listFiles returns files for owned entries`() =
            runTest {
                val rows =
                    listOf(
                        mapOf(
                            "id" to 1L,
                            "name" to "file1.txt",
                            "relPath" to "Download/",
                            "size" to 512L,
                            "dateModified" to 1700000000L,
                            "mimeType" to "text/plain",
                        ),
                        mapOf(
                            "id" to 2L,
                            "name" to "file2.txt",
                            "relPath" to "Download/",
                            "size" to 256L,
                            "dateModified" to 1700000001L,
                            "mimeType" to "text/plain",
                        ),
                    )
                stubQueryReturning(createMockCursor(rows))

                val result = operations.listFiles("builtin:downloads", "", 0, 50)

                assertEquals(2, result.totalCount)
                assertEquals("file1.txt", result.files[0].name)
                assertEquals("file2.txt", result.files[1].name)
            }

        @Test
        fun `listFiles synthesizes directories from RELATIVE_PATH`() =
            runTest {
                val rows =
                    listOf(
                        mapOf(
                            "id" to 1L,
                            "name" to "photo.jpg",
                            "relPath" to "Download/subdir/",
                            "size" to 2048L,
                            "dateModified" to 1700000000L,
                            "mimeType" to "image/jpeg",
                        ),
                    )
                stubQueryReturning(createMockCursor(rows))

                val result = operations.listFiles("builtin:downloads", "", 0, 50)

                assertEquals(1, result.totalCount)
                assertTrue(result.files[0].isDirectory)
                assertEquals("subdir", result.files[0].name)
            }

        @Test
        fun `listFiles applies pagination`() =
            runTest {
                val rows =
                    (1..5).map { i ->
                        mapOf(
                            "id" to i.toLong(),
                            "name" to "file$i.txt",
                            "relPath" to "Download/",
                            "size" to 100L,
                            "dateModified" to 1700000000L,
                            "mimeType" to "text/plain",
                        )
                    }
                stubQueryReturning(createMockCursor(rows))

                val result = operations.listFiles("builtin:downloads", "", 2, 2)

                assertEquals(5, result.totalCount)
                assertEquals(2, result.files.size)
                assertTrue(result.hasMore)
            }

        @Test
        fun `listFiles returns all files in all-files mode`() =
            runTest {
                coEvery { mockStorageLocationProvider.isAllFilesMode("builtin:downloads") } returns true

                val rows =
                    listOf(
                        mapOf(
                            "id" to 1L,
                            "name" to "other.txt",
                            "relPath" to "Download/",
                            "size" to 100L,
                            "dateModified" to 1700000000L,
                            "mimeType" to "text/plain",
                        ),
                    )

                var capturedSelection: String? = null
                every {
                    mockContentResolver.query(any(), any(), any(), any(), any())
                } answers {
                    capturedSelection = arg(2)
                    createMockCursor(rows)
                }

                val result = operations.listFiles("builtin:downloads", "", 0, 50)

                assertEquals(1, result.totalCount)
                assertFalse(capturedSelection.orEmpty().contains("OWNER_PACKAGE_NAME"))
            }

        @Test
        fun `listFiles throws for unknown builtin ID`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        operations.listFiles("builtin:nonexistent", "", 0, 50)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }
    }

    // ─── readFile ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFile")
    inner class ReadFile {
        @Test
        fun `readFile reads owned file content`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = true)
                stubFileSizeQuery(size = 100L)

                val content = "Hello, World!\nSecond line"
                every {
                    mockContentResolver.openInputStream(any())
                } returns ByteArrayInputStream(content.toByteArray())

                val result = operations.readFile("builtin:downloads", "test.txt", 1, 200)

                assertEquals("Hello, World!\nSecond line", result.content)
                assertEquals(2, result.totalLines)
                assertFalse(result.hasMore)
            }

        @Test
        fun `readFile applies line pagination`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = true)
                stubFileSizeQuery(size = 100L)

                val content = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
                every {
                    mockContentResolver.openInputStream(any())
                } returns ByteArrayInputStream(content.toByteArray())

                val result = operations.readFile("builtin:downloads", "test.txt", 2, 2)

                assertEquals("Line 2\nLine 3", result.content)
                assertEquals(5, result.totalLines)
                assertTrue(result.hasMore)
                assertEquals(2, result.startLine)
                assertEquals(3, result.endLine)
            }

        @Test
        fun `readFile throws when file not found`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = false)

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        operations.readFile("builtin:downloads", "missing.txt", 1, 200)
                    }
                assertTrue(exception.message!!.contains("File not found"))
            }

        @Test
        fun `readFile works in all-files mode for non-owned file`() =
            runTest {
                coEvery { mockStorageLocationProvider.isAllFilesMode("builtin:downloads") } returns true

                val findCursor = createMockCursor(listOf(mapOf("id" to 1L)))
                every {
                    mockContentResolver.query(any(), any(), any(), any(), any())
                } returns findCursor

                stubFileSizeQuery(size = 50L)

                val content = "Non-owned content"
                every {
                    mockContentResolver.openInputStream(any())
                } returns ByteArrayInputStream(content.toByteArray())

                val result = operations.readFile("builtin:downloads", "other.txt", 1, 200)

                assertEquals("Non-owned content", result.content)
            }
    }

    // ─── writeFile ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeFile")
    inner class WriteFile {
        @Test
        fun `writeFile creates new MediaStore entry`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = false)

                val insertedUri = mockk<Uri>(relaxed = true)
                every { mockContentResolver.insert(any(), any()) } returns insertedUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(eq(insertedUri), eq("wt")) } returns outputStream

                operations.writeFile("builtin:downloads", "new-file.txt", "Hello")

                assertEquals("Hello", outputStream.toString(Charsets.UTF_8.name()))
                verify { mockContentResolver.insert(eq(fakeCollectionUri), any()) }
            }

        @Test
        fun `writeFile overwrites existing owned file`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = true)

                val outputStream = ByteArrayOutputStream()
                every {
                    mockContentResolver.openOutputStream(eq(fakeFileUri), eq("wt"))
                } returns outputStream

                operations.writeFile("builtin:downloads", "existing.txt", "Updated")

                assertEquals("Updated", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `writeFile throws when write not allowed`() =
            runTest {
                coEvery { mockStorageLocationProvider.isWriteAllowed("builtin:downloads") } returns false

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        operations.writeFile("builtin:downloads", "test.txt", "content")
                    }
                assertTrue(exception.message!!.contains("Write not allowed"))
            }

        @Test
        fun `writeFile rejects path traversal`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    operations.writeFile("builtin:downloads", "../etc/passwd", "bad")
                }
            }

        @Test
        fun `writeFile respects file size limit`() =
            runTest {
                coEvery { mockSettingsRepository.getServerConfig() } returns ServerConfig(fileSizeLimitMb = 1)

                val largeContent = "x".repeat(1_048_577)

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        operations.writeFile("builtin:downloads", "large.txt", largeContent)
                    }
                assertTrue(exception.message!!.contains("file size limit"))
            }
    }

    // ─── appendFile ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("appendFile")
    inner class AppendFile {
        @Test
        fun `appendFile appends to owned file`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = true)
                stubFileSizeQuery(size = 50L)

                val outputStream = ByteArrayOutputStream()
                every {
                    mockContentResolver.openOutputStream(eq(fakeFileUri), eq("wa"))
                } returns outputStream

                operations.appendFile("builtin:downloads", "test.txt", " appended")

                assertEquals(" appended", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `appendFile throws when write not allowed`() =
            runTest {
                coEvery { mockStorageLocationProvider.isWriteAllowed("builtin:downloads") } returns false

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        operations.appendFile("builtin:downloads", "test.txt", "content")
                    }
                assertTrue(exception.message!!.contains("Write not allowed"))
            }
    }

    // ─── replaceInFile ──────────────────────────────────────────────────

    @Nested
    @DisplayName("replaceInFile")
    inner class ReplaceInFile {
        @Test
        fun `replaceInFile performs replacement on owned file`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = true)
                stubFileSizeQuery(size = 100L)

                val originalContent = "Hello World Hello"
                every {
                    mockContentResolver.openInputStream(any())
                } returns ByteArrayInputStream(originalContent.toByteArray())

                val outputStream = ByteArrayOutputStream()
                every {
                    mockContentResolver.openOutputStream(eq(fakeFileUri), eq("wt"))
                } returns outputStream

                val result =
                    operations.replaceInFile(
                        "builtin:downloads",
                        "test.txt",
                        "Hello",
                        "Hi",
                        replaceAll = true,
                    )

                assertEquals(2, result.replacementCount)
                assertEquals("Hi World Hi", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `replaceInFile throws for non-owned file`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = false)

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        operations.replaceInFile(
                            "builtin:downloads",
                            "missing.txt",
                            "old",
                            "new",
                            replaceAll = false,
                        )
                    }
                assertTrue(exception.message!!.contains("File not found"))
            }
    }

    // ─── deleteFile ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteFile")
    inner class DeleteFile {
        @Test
        fun `deleteFile deletes owned file`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = true)
                every { mockContentResolver.delete(any(), any(), any()) } returns 1

                operations.deleteFile("builtin:downloads", "test.txt")

                verify { mockContentResolver.delete(eq(fakeFileUri), any(), any()) }
            }

        @Test
        fun `deleteFile throws when delete not allowed`() =
            runTest {
                coEvery { mockStorageLocationProvider.isDeleteAllowed("builtin:downloads") } returns false

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        operations.deleteFile("builtin:downloads", "test.txt")
                    }
                assertTrue(exception.message!!.contains("Delete not allowed"))
            }
    }

    // ─── createFileUri ──────────────────────────────────────────────────

    @Nested
    @DisplayName("createFileUri")
    inner class CreateFileUri {
        @Test
        fun `createFileUri returns URI for new entry`() =
            runTest {
                stubFindOwnedFile(id = 1L, found = false)

                val insertedUri = mockk<Uri>(relaxed = true)
                every { mockContentResolver.insert(any(), any()) } returns insertedUri

                val result =
                    operations.createFileUri("builtin:downloads", "photo.jpg", "image/jpeg")

                assertEquals(insertedUri, result)
                verify { mockContentResolver.insert(eq(fakeCollectionUri), any()) }
            }

        @Test
        fun `createFileUri returns existing URI when file exists`() =
            runTest {
                stubFindOwnedFile(id = 7L, found = true)

                val result =
                    operations.createFileUri("builtin:downloads", "existing.jpg", "image/jpeg")

                assertEquals(fakeFileUri, result)
                verify(exactly = 0) { mockContentResolver.insert(any(), any()) }
            }
    }

    // ─── Validation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("validation")
    inner class Validation {
        @Test
        fun `all methods reject empty path for file operations`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    operations.readFile("builtin:downloads", "", 1, 200)
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.writeFile("builtin:downloads", "", "content")
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.deleteFile("builtin:downloads", "")
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.createFileUri("builtin:downloads", "", "text/plain")
                }
            }

        @Test
        fun `all methods reject path traversal`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    operations.listFiles("builtin:downloads", "../secret", 0, 50)
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.readFile("builtin:downloads", "../secret/file.txt", 1, 200)
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.writeFile("builtin:downloads", "sub/../../../etc/passwd", "bad")
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.appendFile("builtin:downloads", "../file.txt", "data")
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.replaceInFile(
                        "builtin:downloads",
                        "../file.txt",
                        "old",
                        "new",
                        false,
                    )
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.deleteFile("builtin:downloads", "../file.txt")
                }
                assertThrows<McpToolException.InvalidParams> {
                    operations.createFileUri("builtin:downloads", "../file.txt", "text/plain")
                }
            }
    }
}
