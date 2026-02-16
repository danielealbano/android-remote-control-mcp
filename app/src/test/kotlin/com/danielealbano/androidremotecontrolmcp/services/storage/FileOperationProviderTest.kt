@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
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
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@ExtendWith(MockKExtension::class)
@DisplayName("FileOperationProviderImpl")
class FileOperationProviderTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockStorageLocationProvider: StorageLocationProvider

    @MockK
    private lateinit var mockSettingsRepository: SettingsRepository

    private lateinit var provider: FileOperationProviderImpl

    private val mockTreeUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(DocumentFile::class)

        every { mockContext.contentResolver } returns mockContentResolver

        provider =
            FileOperationProviderImpl(
                mockContext,
                mockStorageLocationProvider,
                mockSettingsRepository,
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
        unmockkStatic(DocumentFile::class)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Sets up mocks so that [locationId] is treated as authorized.
     * [storageLocationProvider.isLocationAuthorized] returns true and
     * [storageLocationProvider.getTreeUriForLocation] returns the shared [mockTreeUri].
     */
    private fun setupAuthorizedLocation(locationId: String) {
        coEvery { mockStorageLocationProvider.isLocationAuthorized(locationId) } returns true
        coEvery { mockStorageLocationProvider.getTreeUriForLocation(locationId) } returns mockTreeUri
        coEvery { mockStorageLocationProvider.isWriteAllowed(locationId) } returns true
        coEvery { mockStorageLocationProvider.isDeleteAllowed(locationId) } returns true
    }

    /**
     * Sets up mocks so that [locationId] is treated as unauthorized.
     */
    private fun setupUnauthorizedLocation(locationId: String) {
        coEvery { mockStorageLocationProvider.isLocationAuthorized(locationId) } returns false
    }

    /**
     * Configures [DocumentFile.fromTreeUri] to return [rootDoc] for the shared [mockTreeUri].
     */
    private fun setupRootDocument(rootDoc: DocumentFile) {
        every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns rootDoc
    }

    // ─────────────────────────────────────────────────────────────────────
    // listFiles
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listFiles")
    inner class ListFiles {
        @Test
        fun `listFiles returns entries for authorized location`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true

                val dirChild =
                    mockk<DocumentFile> {
                        every { name } returns "subdir"
                        every { isDirectory } returns true
                        every { isFile } returns false
                        every { lastModified() } returns 1000L
                        every { type } returns null
                    }
                val fileChild =
                    mockk<DocumentFile> {
                        every { name } returns "test.txt"
                        every { isDirectory } returns false
                        every { isFile } returns true
                        every { length() } returns 500L
                        every { lastModified() } returns 2000L
                        every { type } returns "text/plain"
                    }
                every { mockDir.listFiles() } returns arrayOf(dirChild, fileChild)

                // Act
                val result = provider.listFiles("loc1", "", 0, 10)

                // Assert
                assertEquals(2, result.files.size)
                assertEquals(2, result.totalCount)
                assertFalse(result.hasMore)

                // Directories come first in sort order
                val dir = result.files[0]
                assertEquals("subdir", dir.name)
                assertEquals("loc1/subdir", dir.path)
                assertTrue(dir.isDirectory)
                assertEquals(0L, dir.size)

                val file = result.files[1]
                assertEquals("test.txt", file.name)
                assertEquals("loc1/test.txt", file.path)
                assertFalse(file.isDirectory)
                assertEquals(500L, file.size)
                assertEquals("text/plain", file.mimeType)
            }

        @Test
        fun `listFiles throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.listFiles("loc1", "", 0, 10)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `listFiles respects offset and limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true

                val children =
                    ('a'..'e')
                        .map { letter ->
                            mockk<DocumentFile> {
                                every { name } returns "file_$letter.txt"
                                every { isDirectory } returns false
                                every { isFile } returns true
                                every { length() } returns 100L
                                every { lastModified() } returns 1000L
                                every { type } returns "text/plain"
                            }
                        }.toTypedArray()
                every { mockDir.listFiles() } returns children

                // Act — skip 2, take 2 from 5 total
                val result = provider.listFiles("loc1", "", 2, 2)

                // Assert
                assertEquals(2, result.files.size)
                assertEquals("file_c.txt", result.files[0].name)
                assertEquals("file_d.txt", result.files[1].name)
                assertEquals(5, result.totalCount)
                assertTrue(result.hasMore)
            }

        @Test
        fun `listFiles caps limit at MAX_LIST_ENTRIES`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true

                val children =
                    (1..LIST_ENTRIES_ABOVE_MAX)
                        .map { i ->
                            mockk<DocumentFile> {
                                every { name } returns "file_%03d.txt".format(i)
                                every { isDirectory } returns false
                                every { isFile } returns true
                                every { length() } returns 100L
                                every { lastModified() } returns 1000L
                                every { type } returns "text/plain"
                            }
                        }.toTypedArray()
                every { mockDir.listFiles() } returns children

                // Act — request limit far above MAX_LIST_ENTRIES
                val result = provider.listFiles("loc1", "", 0, REQUESTED_LIMIT_ABOVE_MAX)

                // Assert — capped at 200
                assertEquals(FileOperationProvider.MAX_LIST_ENTRIES, result.files.size)
                assertEquals(LIST_ENTRIES_ABOVE_MAX, result.totalCount)
                assertTrue(result.hasMore)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // readFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFile")
    inner class ReadFile {
        @Test
        fun `readFile returns lines with pagination info`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("data.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 100L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val content = "line1\nline2\nline3\nline4\nline5"
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — read lines starting at line 2, limit 2
                val result = provider.readFile("loc1", "data.txt", 2, 2)

                // Assert
                assertEquals("line2\nline3", result.content)
                assertEquals(5, result.totalLines)
                assertEquals(2, result.startLine)
                assertEquals(3, result.endLine)
                assertTrue(result.hasMore)
            }

        @Test
        fun `readFile caps limit at MAX_READ_LINES`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("big.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 10_000L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val lines = (1..LINES_ABOVE_MAX).map { "line$it" }
                val content = lines.joinToString("\n")
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — request limit far above MAX_READ_LINES
                val result = provider.readFile("loc1", "big.txt", 1, REQUESTED_LIMIT_ABOVE_MAX)

                // Assert — capped at 200 lines
                assertEquals(
                    FileOperationProvider.MAX_READ_LINES,
                    result.content.split("\n").size,
                )
                assertEquals(LINES_ABOVE_MAX, result.totalLines)
                assertTrue(result.hasMore)
            }

        @Test
        fun `readFile includes hasMore hint when more lines exist`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("file.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 50L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val content = "line1\nline2\nline3"
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — read 2 of 3 lines
                val result = provider.readFile("loc1", "file.txt", 1, 2)

                // Assert
                assertEquals("line1\nline2", result.content)
                assertEquals(3, result.totalLines)
                assertTrue(result.hasMore)
            }

        @Test
        fun `readFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.readFile("loc1", "file.txt", 1, 100)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `readFile throws ActionFailed when file exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                every { mockRootDoc.findFile("huge.bin") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns FILE_SIZE_EXCEEDING_LIMIT
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)

                // Act & Assert — 100 MB > 1 MB limit
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.readFile("loc1", "huge.bin", 1, 100)
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
            }

        @Test
        fun `readFile streams lines without loading entire file into memory`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("multiline.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 10_000L
                every { mockFile.uri } returns mockFileUri
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

                val lines = (1..50).map { "line$it" }
                val content = lines.joinToString("\n")
                every { mockContentResolver.openInputStream(mockFileUri) } returns
                    ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

                // Act — read 10 lines starting at line 10
                val result = provider.readFile("loc1", "multiline.txt", 10, 10)

                // Assert — streaming produces correct offset/limit window
                assertEquals(50, result.totalLines)
                assertEquals(10, result.startLine)
                assertEquals(19, result.endLine)
                assertTrue(result.hasMore)
                val returnedLines = result.content.split("\n")
                assertEquals(10, returnedLines.size)
                assertEquals("line10", returnedLines[0])
                assertEquals("line19", returnedLines[9])
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // writeFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeFile")
    inner class WriteFile {
        @Test
        fun `writeFile creates file with content`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("file.txt") } returns null
                every { mockRootDoc.createFile("text/plain", "file.txt") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                provider.writeFile("loc1", "file.txt", "hello world")

                // Assert
                assertEquals("hello world", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `writeFile with empty content creates empty file`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("empty.txt") } returns null
                every { mockRootDoc.createFile("text/plain", "empty.txt") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                provider.writeFile("loc1", "empty.txt", "")

                // Assert
                assertEquals(0, outputStream.size())
            }

        @Test
        fun `writeFile throws ActionFailed when content exceeds size limit`() =
            runTest {
                // Arrange — 1 MB limit, content > 1 MB
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)
                val largeContent = "x".repeat(BYTES_PER_MB + 1)

                // Act & Assert — size check happens before authorization
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.writeFile("loc1", "file.txt", largeContent)
                    }
                assertTrue(exception.message!!.contains("exceeds the configured file size limit"))
            }

        @Test
        fun `writeFile creates parent directories`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)

                val subDirDoc = mockk<DocumentFile>()
                every { rootDoc.findFile("subdir") } returns null
                every { rootDoc.createDirectory("subdir") } returns subDirDoc

                val createdFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { subDirDoc.findFile("file.txt") } returns null
                every { subDirDoc.createFile("text/plain", "file.txt") } returns createdFile
                every { createdFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                provider.writeFile("loc1", "subdir/file.txt", "content")

                // Assert
                verify { rootDoc.createDirectory("subdir") }
                assertEquals("content", outputStream.toString(Charsets.UTF_8.name()))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // appendFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("appendFile")
    inner class AppendFile {
        @Test
        fun `appendFile throws ActionFailed with hint when provider does not support append`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("file.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 100L
                every { mockFile.uri } returns mockFileUri

                every {
                    mockContentResolver.openOutputStream(mockFileUri, "wa")
                } throws UnsupportedOperationException("Append not supported")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.appendFile("loc1", "file.txt", "more data")
                    }
                assertTrue(exception.message!!.contains("does not support append mode"))
                assertTrue(exception.message!!.contains("write_file"))
            }

        @Test
        fun `appendFile throws ActionFailed when resulting size exceeds limit`() =
            runTest {
                // Arrange — file already at 1 MB limit, appending 1 more byte exceeds it
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockFile = mockk<DocumentFile>()
                every { mockRootDoc.findFile("file.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns BYTES_PER_MB.toLong()

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.appendFile("loc1", "file.txt", "a")
                    }
                assertTrue(exception.message!!.contains("exceed"))
            }

        @Test
        fun `appendFile appends content to existing file`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("file.txt") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 100L
                every { mockFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "wa") } returns outputStream

                // Act
                provider.appendFile("loc1", "file.txt", "appended data")

                // Assert
                assertEquals("appended data", outputStream.toString(Charsets.UTF_8.name()))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // replaceInFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replaceInFile")
    inner class ReplaceInFile {
        /**
         * Common setup for replace tests: resolves a file with the given [originalContent],
         * stubs the read + write mocks, and returns the [ByteArrayOutputStream] that
         * captures the written-back content.
         */
        private fun setupReplaceFile(originalContent: String): ByteArrayOutputStream {
            setupAuthorizedLocation("loc1")
            val mockRootDoc = mockk<DocumentFile>()
            setupRootDocument(mockRootDoc)

            val mockFile = mockk<DocumentFile>()
            val mockFileUri = mockk<Uri>()
            every { mockRootDoc.findFile("file.txt") } returns mockFile
            every { mockFile.isFile } returns true
            every { mockFile.isDirectory } returns false
            every { mockFile.length() } returns originalContent.length.toLong()
            every { mockFile.uri } returns mockFileUri
            coEvery { mockSettingsRepository.getServerConfig() } returns
                ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)

            every { mockContentResolver.openInputStream(mockFileUri) } returns
                ByteArrayInputStream(originalContent.toByteArray(Charsets.UTF_8))

            // writeWithOptionalLock: openFileDescriptor returns null → OutputStream fallback
            every { mockContentResolver.openFileDescriptor(any(), any()) } returns null
            val outputStream = ByteArrayOutputStream()
            every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream
            return outputStream
        }

        @Test
        fun `replaceInFile replaces first occurrence`() =
            runTest {
                // Arrange
                val outputStream = setupReplaceFile("hello world hello")

                // Act
                val result =
                    provider.replaceInFile(
                        "loc1",
                        "file.txt",
                        "hello",
                        "hi",
                        false,
                    )

                // Assert
                assertEquals(1, result.replacementCount)
                assertEquals("hi world hello", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `replaceInFile replaces all occurrences when replaceAll is true`() =
            runTest {
                // Arrange
                val outputStream = setupReplaceFile("hello world hello")

                // Act
                val result =
                    provider.replaceInFile(
                        "loc1",
                        "file.txt",
                        "hello",
                        "hi",
                        true,
                    )

                // Assert
                assertEquals(2, result.replacementCount)
                assertEquals("hi world hi", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `replaceInFile returns zero count when old_string not found`() =
            runTest {
                // Arrange
                setupReplaceFile("hello world")

                // Act
                val result =
                    provider.replaceInFile(
                        "loc1",
                        "file.txt",
                        "xyz",
                        "abc",
                        false,
                    )

                // Assert
                assertEquals(0, result.replacementCount)
                // No write should have occurred
                verify(exactly = 0) { mockContentResolver.openOutputStream(any(), any()) }
                verify(exactly = 0) { mockContentResolver.openFileDescriptor(any(), any()) }
            }

        @Test
        fun `replaceInFile throws ActionFailed when file exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                every { mockRootDoc.findFile("big.bin") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns FILE_SIZE_EXCEEDING_LIMIT
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.replaceInFile("loc1", "big.bin", "a", "b", false)
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // downloadFromUrl
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("downloadFromUrl")
    inner class DownloadFromUrl {
        @Test
        fun `downloadFromUrl throws ActionFailed when HTTP not allowed`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = false)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "http://example.com/file.txt")
                    }
                assertTrue(exception.message!!.contains("HTTP downloads are not allowed"))
            }

        @Test
        fun `downloadFromUrl throws ActionFailed for invalid URL`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = true)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "not_a_valid_url")
                    }
                assertTrue(exception.message!!.contains("Invalid URL"))
            }

        @Test
        fun `downloadFromUrl throws ActionFailed when Content-Length exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1, allowHttpDownloads = true)

                val mockConnection = mockk<HttpURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns (2L * BYTES_PER_MB)

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        spyProvider.downloadFromUrl(
                            "loc1",
                            "file.txt",
                            "https://example.com/large.bin",
                        )
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
            }

        @Test
        fun `downloadFromUrl downloads file and saves to location`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = true)

                val mockConnection = mockk<HttpURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns 5L
                every { mockConnection.inputStream } returns
                    ByteArrayInputStream("hello".toByteArray())

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { rootDoc.findFile("file.txt") } returns null
                every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
                every { mockFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                val size =
                    spyProvider.downloadFromUrl(
                        "loc1",
                        "file.txt",
                        "https://example.com/file.txt",
                    )

                // Assert
                assertEquals(5L, size)
                assertEquals("hello", outputStream.toString(Charsets.UTF_8.name()))
            }

        @Test
        fun `downloadFromUrl throws ActionFailed when streamed size exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1, allowHttpDownloads = true)

                val mockConnection = mockk<HttpURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns -1L
                every { mockConnection.inputStream } returns
                    ByteArrayInputStream(ByteArray(BYTES_PER_MB + 1))

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { rootDoc.findFile("file.txt") } returns null
                every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
                every { mockFile.uri } returns mockFileUri
                every { mockFile.delete() } returns true

                val outputStream = ByteArrayOutputStream()
                every {
                    mockContentResolver.openOutputStream(mockFileUri, "w")
                } returns outputStream

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        spyProvider.downloadFromUrl(
                            "loc1",
                            "file.txt",
                            "https://example.com/big.bin",
                        )
                    }
                assertTrue(
                    exception.message!!.contains("exceeds the configured file size limit"),
                )
            }

        @Test
        fun `downloadFromUrl uses permissive SSL when unverified certs allowed`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(
                        fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB,
                        allowHttpDownloads = true,
                        allowUnverifiedHttpsCerts = true,
                    )

                val mockConnection = mockk<HttpsURLConnection>(relaxed = true)
                every { mockConnection.responseCode } returns 200
                every { mockConnection.contentLengthLong } returns 5L
                every { mockConnection.inputStream } returns
                    ByteArrayInputStream("hello".toByteArray())

                val mockUrl = mockk<URL>()
                every { mockUrl.protocol } returns "https"

                val spyProvider =
                    spyk(provider)
                every { spyProvider.openUrlConnection(any()) } returns (mockUrl to mockConnection)

                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { rootDoc.findFile("file.txt") } returns null
                every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
                every { mockFile.uri } returns mockFileUri

                val outputStream = ByteArrayOutputStream()
                every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream

                // Act
                spyProvider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")

                // Assert — permissive SSL was configured
                verify { mockConnection.sslSocketFactory = any() }
                verify { mockConnection.hostnameVerifier = any() }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // deleteFile
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteFile")
    inner class DeleteFile {
        @Test
        fun `deleteFile deletes the file`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                every { mockRootDoc.findFile("file.txt") } returns mockFile
                every { mockFile.isDirectory } returns false
                every { mockFile.delete() } returns true

                // Act
                provider.deleteFile("loc1", "file.txt")

                // Assert
                verify { mockFile.delete() }
            }

        @Test
        fun `deleteFile throws ActionFailed when path is a directory`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockDir = mockk<DocumentFile>()
                every { mockRootDoc.findFile("subdir") } returns mockDir
                every { mockDir.isDirectory } returns true

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.deleteFile("loc1", "subdir")
                    }
                assertTrue(exception.message!!.contains("Cannot delete a directory"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WritePermissionDenied
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WritePermissionDenied")
    inner class WritePermissionDenied {
        @Test
        fun `writeFile throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.writeFile("loc1", "file.txt", "small content")
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `appendFile throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.appendFile("loc1", "file.txt", "more data")
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `replaceInFile throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.replaceInFile("loc1", "file.txt", "old", "new", false)
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `downloadFromUrl throws PermissionDenied when write not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")
                    }
                assertEquals("Write not allowed", exception.message)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DeletePermissionDenied
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DeletePermissionDenied")
    inner class DeletePermissionDenied {
        @Test
        fun `deleteFile throws PermissionDenied when delete not allowed`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.isDeleteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.deleteFile("loc1", "file.txt")
                    }
                assertEquals("Delete not allowed", exception.message)
            }
    }

    companion object {
        private const val BYTES_PER_MB = 1024 * 1024
        private const val DEFAULT_FILE_SIZE_LIMIT_MB = 50
        private const val FILE_SIZE_EXCEEDING_LIMIT = 100_000_000L
        private const val LIST_ENTRIES_ABOVE_MAX = 210
        private const val LINES_ABOVE_MAX = 210
        private const val REQUESTED_LIMIT_ABOVE_MAX = 300
    }
}
