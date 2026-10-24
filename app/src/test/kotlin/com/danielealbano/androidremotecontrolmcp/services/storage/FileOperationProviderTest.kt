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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors

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

    @MockK
    private lateinit var mockMediaStoreFileOperations: MediaStoreFileOperations

    @MockK
    private lateinit var mockDownloadHttpClient: DownloadHttpClient

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
                mockMediaStoreFileOperations,
                mockDownloadHttpClient,
                Dispatchers.Unconfined,
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
        fun `readFile throws IllegalArgumentException when offset is less than 1`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<IllegalArgumentException> {
                        provider.readFile("loc1", "file.txt", 0, 100)
                    }
                assertTrue(exception.message!!.contains("offset must be >= 1"))
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
    // readFileBytes
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFileBytes")
    inner class ReadFileBytes {
        @Test
        fun `readFileBytes reads existing file without creating it`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("doc.pdf") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns 4L
                every { mockFile.uri } returns mockFileUri
                every { mockFile.name } returns "doc.pdf"
                every { mockContentResolver.getType(mockFileUri) } returns "application/pdf"
                val bytes = byteArrayOf(1, 2, 3, 4)
                every { mockContentResolver.openInputStream(mockFileUri) } returns ByteArrayInputStream(bytes)

                // Act
                val result = provider.readFileBytes("loc1", "doc.pdf", TEN_MB)

                // Assert
                assertArrayEquals(bytes, result.bytes)
                assertEquals("application/pdf", result.mimeType)
                assertEquals("doc.pdf", result.fileName)
                assertEquals(4L, result.sizeBytes)
                verify(exactly = 0) { mockRootDoc.createFile(any(), any()) }
            }

        @Test
        fun `readFileBytes throws ActionFailed for missing file`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                every { mockRootDoc.findFile("missing.pdf") } returns null

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.readFileBytes("loc1", "missing.pdf", TEN_MB)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `readFileBytes throws ActionFailed when file exceeds maxBytes`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)
                val mockFile = mockk<DocumentFile>()
                every { mockRootDoc.findFile("huge.bin") } returns mockFile
                every { mockFile.isFile } returns true
                every { mockFile.length() } returns FILE_SIZE_EXCEEDING_LIMIT
                every { mockFile.uri } returns mockk()
                every { mockFile.name } returns "huge.bin"

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.readFileBytes("loc1", "huge.bin", TEN_MB)
                    }
                assertTrue(exception.message!!.contains("exceeds"))
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
        /** Sets up loc1 so that `file.txt` is created and returns the stream the download is written to. */
        private fun setupDestinationFile(deletable: Boolean = false): Pair<DocumentFile, ByteArrayOutputStream> {
            val rootDoc = mockk<DocumentFile>()
            setupRootDocument(rootDoc)
            val mockFile = mockk<DocumentFile>()
            val mockFileUri = mockk<Uri>()
            every { rootDoc.findFile("file.txt") } returns null
            every { rootDoc.createFile("text/plain", "file.txt") } returns mockFile
            every { mockFile.uri } returns mockFileUri
            if (deletable) every { mockFile.delete() } returns true
            val outputStream = ByteArrayOutputStream()
            every { mockContentResolver.openOutputStream(mockFileUri, "w") } returns outputStream
            return mockFile to outputStream
        }

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
                coVerify(exactly = 0) { mockDownloadHttpClient.download<Any?>(any(), any(), any()) }
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
                val response = FakeDownloadResponse(200, 2L * BYTES_PER_MB)
                mockDownloadHttpClient.stubDownload(response)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/large.bin")
                    }
                assertTrue(exception.message!!.contains("exceeds the configured limit"))
                assertTrue(response.closed)
            }

        @Test
        fun `downloadFromUrl downloads file and saves to location`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, allowHttpDownloads = true)
                val response = FakeDownloadResponse(200, 5L, ByteArrayInputStream("hello".toByteArray()))
                mockDownloadHttpClient.stubDownload(response)
                val (_, outputStream) = setupDestinationFile()

                // Act
                val size = provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")

                // Assert
                assertEquals(5L, size)
                assertEquals("hello", outputStream.toString(Charsets.UTF_8.name()))
                assertTrue(response.closed)
            }

        @Test
        fun `downloadFromUrl passes the URL and the download settings to the http client`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val config =
                    ServerConfig(
                        fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB,
                        allowUnverifiedHttpsCerts = true,
                        blockLocalNetworkDownloads = true,
                    )
                coEvery { mockSettingsRepository.getServerConfig() } returns config
                mockDownloadHttpClient.stubDownload(FakeDownloadResponse(200, 0L))
                setupDestinationFile()

                // Act
                provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")

                // Assert
                coVerify {
                    mockDownloadHttpClient.download<Any?>(
                        match { it.toString() == "https://example.com/file.txt" },
                        config,
                        any(),
                    )
                }
            }

        @Test
        fun `downloadFromUrl throws ActionFailed when streamed size exceeds size limit`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = 1, allowHttpDownloads = true)
                mockDownloadHttpClient.stubDownload(
                    FakeDownloadResponse(200, -1L, ByteArrayInputStream(ByteArray(BYTES_PER_MB + 1))),
                )
                val (mockFile, _) = setupDestinationFile(deletable = true)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/big.bin")
                    }
                assertTrue(exception.message!!.contains("exceeds the configured file size limit"))
                verify { mockFile.delete() }
            }

        @Test
        fun `downloadFromUrl reports an HTTP error status and closes the response`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                val response = FakeDownloadResponse(404)
                mockDownloadHttpClient.stubDownload(response)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/missing.txt")
                    }
                assertTrue(exception.message!!.contains("HTTP status 404"))
                assertTrue(response.closed)
                verify(exactly = 0) { mockContentResolver.openOutputStream(any(), any()) }
            }

        @Test
        fun `downloadFromUrl deletes the partial file and closes the response when the stream fails`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                val failingBody =
                    object : InputStream() {
                        private var sent = false

                        override fun read(): Int = throw IOException("connection reset")

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int {
                            if (sent) throw IOException("connection reset")
                            sent = true
                            b[off] = 1
                            return 1
                        }
                    }
                val response = FakeDownloadResponse(200, -1L, failingBody)
                mockDownloadHttpClient.stubDownload(response)
                val (mockFile, _) = setupDestinationFile(deletable = true)

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")
                    }
                assertTrue(exception.message!!.contains("Download failed: connection reset"))
                verify { mockFile.delete() }
                assertTrue(response.closed)
            }

        @Test
        fun `a cancelled downloadFromUrl stops, deletes the partial file and stays a cancellation`() =
            runBlocking {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                lateinit var request: Job
                mockDownloadHttpClient.stubDownload(FakeDownloadResponse(200, -1L, cancellingBody { request.cancel() }))
                val (mockFile, _) = setupDestinationFile(deletable = true)
                var outcome: Throwable? = null

                // Act
                request =
                    launch {
                        try {
                            provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")
                        } catch (e: CancellationException) {
                            outcome = e
                            throw e
                        }
                    }
                request.join()

                // Assert
                assertTrue(request.isCancelled)
                assertTrue(outcome is CancellationException, "the cancellation became $outcome")
                verify { mockFile.delete() }
            }

        @Test
        fun `downloadFromUrl reports a blocked local network connection without creating the file`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB, blockLocalNetworkDownloads = true)
                coEvery { mockDownloadHttpClient.download<Any?>(any(), any(), any()) } throws
                    LocalNetworkDownloadBlockedException(InetSocketAddress("192.168.1.1", 443))

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://192.168.1.1/file.txt")
                    }
                assertTrue(exception.message!!.contains("Download failed"))
                assertTrue(exception.message!!.contains("192.168.1.1:443"))
                assertTrue(exception.message!!.contains("Block Local Network Downloads"))
                verify(exactly = 0) { mockContentResolver.openOutputStream(any(), any()) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // I/O dispatcher
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("I/O dispatcher")
    inner class RunsOnIoDispatcher {
        /** Runs [block] with a provider whose I/O dispatcher is a single thread named [IO_THREAD]. */
        private fun withIoThread(block: suspend (FileOperationProviderImpl) -> Unit) {
            val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
            try {
                val onIoThread =
                    FileOperationProviderImpl(
                        mockContext,
                        mockStorageLocationProvider,
                        mockSettingsRepository,
                        mockMediaStoreFileOperations,
                        mockDownloadHttpClient,
                        executor.asCoroutineDispatcher(),
                    )
                runBlocking { block(onIoThread) }
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `a download runs its network I-O on the I-O dispatcher`() {
            setupAuthorizedLocation("loc1")
            coEvery { mockSettingsRepository.getServerConfig() } returns
                ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
            var downloadThread: String? = null
            coEvery { mockDownloadHttpClient.download<Any?>(any(), any(), any()) } answers {
                downloadThread = Thread.currentThread().name
                throw IOException("offline")
            }

            withIoThread { onIoThread ->
                runCatching { onIoThread.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt") }
            }

            // Coroutine debug mode (on in tests: the JVM runs with assertions enabled) appends " @coroutine#N"
            assertTrue(downloadThread.orEmpty().startsWith(IO_THREAD), downloadThread)
        }

        @Test
        fun `every SAF operation runs on the I-O dispatcher`() {
            // Each operation checks the location authorization inside its I/O block: refusing it records the thread
            coEvery { mockSettingsRepository.getServerConfig() } returns
                ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
            val threads = mutableMapOf<String, String>()
            var current = ""
            coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } answers {
                threads[current] = Thread.currentThread().name
                false
            }
            val operations: List<Pair<String, suspend (FileOperationProviderImpl) -> Unit>> =
                listOf(
                    "listFiles" to { it.listFiles("loc1", "", 0, PAGE_LIMIT) },
                    "readFile" to { it.readFile("loc1", "file.txt", 1, PAGE_LIMIT) },
                    "readFileBytes" to { it.readFileBytes("loc1", "file.txt", TEN_MB) },
                    "writeFile" to { it.writeFile("loc1", "file.txt", "x") },
                    "appendFile" to { it.appendFile("loc1", "file.txt", "x") },
                    "replaceInFile" to { it.replaceInFile("loc1", "file.txt", "a", "b", false) },
                    "downloadFromUrl" to { it.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt") },
                    "deleteFile" to { it.deleteFile("loc1", "file.txt") },
                    "createFileUri" to { it.createFileUri("loc1", "file.txt", "text/plain") },
                )

            withIoThread { onIoThread ->
                for ((name, operation) in operations) {
                    current = name
                    val failure = runCatching { operation(onIoThread) }.exceptionOrNull()
                    assertTrue(failure is McpToolException.PermissionDenied, "$name: $failure")
                }
            }

            for ((name, _) in operations) {
                assertTrue(threads[name].orEmpty().startsWith(IO_THREAD), "$name ran on ${threads[name]}")
            }
        }

        @Test
        fun `a write runs its content resolver I-O on the I-O dispatcher`() {
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
            var writeThread: String? = null
            every { mockContentResolver.openOutputStream(mockFileUri, "w") } answers {
                writeThread = Thread.currentThread().name
                ByteArrayOutputStream()
            }

            withIoThread { onIoThread -> onIoThread.writeFile("loc1", "file.txt", "hello") }

            assertTrue(writeThread.orEmpty().startsWith(IO_THREAD), writeThread)
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
    // AuthorizationDeniedForWriteDelete
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AuthorizationDeniedForWriteDelete")
    inner class AuthorizationDeniedForWriteDelete {
        @Test
        fun `writeFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange — getServerConfig() runs before checkAuthorization()
                coEvery { mockSettingsRepository.getServerConfig() } returns
                    ServerConfig(fileSizeLimitMb = DEFAULT_FILE_SIZE_LIMIT_MB)
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.writeFile("loc1", "file.txt", "content")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `appendFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.appendFile("loc1", "file.txt", "content")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `replaceInFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.replaceInFile("loc1", "file.txt", "old", "new", false)
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `downloadFromUrl throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.downloadFromUrl("loc1", "file.txt", "https://example.com/file")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `deleteFile throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.deleteFile("loc1", "file.txt")
                    }
                assertTrue(exception.message!!.contains("not found"))
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

    // ─────────────────────────────────────────────────────────────────────
    // createFileUri
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createFileUri")
    inner class CreateFileUri {
        @Test
        fun `createFileUri returns valid URI for authorized location with write permission`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val mockRootDoc = mockk<DocumentFile>()
                setupRootDocument(mockRootDoc)

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { mockRootDoc.findFile("photo.jpg") } returns null
                every { mockRootDoc.createFile("image/jpeg", "photo.jpg") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                // Act
                val result = provider.createFileUri("loc1", "photo.jpg", "image/jpeg")

                // Assert
                assertEquals(mockFileUri, result)
            }

        @Test
        fun `createFileUri throws PermissionDenied for unauthorized location`() =
            runTest {
                // Arrange
                setupUnauthorizedLocation("loc1")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.createFileUri("loc1", "photo.jpg", "image/jpeg")
                    }
                assertTrue(exception.message!!.contains("not found"))
            }

        @Test
        fun `createFileUri throws PermissionDenied for read-only location`() =
            runTest {
                // Arrange
                coEvery { mockStorageLocationProvider.isLocationAuthorized("loc1") } returns true
                coEvery { mockStorageLocationProvider.getTreeUriForLocation("loc1") } returns mockTreeUri
                coEvery { mockStorageLocationProvider.isWriteAllowed("loc1") } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        provider.createFileUri("loc1", "photo.jpg", "image/jpeg")
                    }
                assertEquals("Write not allowed", exception.message)
            }

        @Test
        fun `createFileUri creates parent directories when they don't exist`() =
            runTest {
                // Arrange
                setupAuthorizedLocation("loc1")
                val rootDoc = mockk<DocumentFile>()
                setupRootDocument(rootDoc)

                val subDirDoc = mockk<DocumentFile>()
                every { rootDoc.findFile("photos") } returns null
                every { rootDoc.createDirectory("photos") } returns subDirDoc

                val mockCreatedFile = mockk<DocumentFile>()
                val mockFileUri = mockk<Uri>()
                every { subDirDoc.findFile("photo.jpg") } returns null
                every { subDirDoc.createFile("image/jpeg", "photo.jpg") } returns mockCreatedFile
                every { mockCreatedFile.uri } returns mockFileUri

                // Act
                val result = provider.createFileUri("loc1", "photos/photo.jpg", "image/jpeg")

                // Assert
                assertEquals(mockFileUri, result)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MediaStore Routing
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MediaStore Routing")
    inner class MediaStoreRouting {
        @Test
        fun `listFiles routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.listFiles("builtin:downloads", "", 0, 200)
                } returns FileListResult(files = emptyList(), totalCount = 0, hasMore = false)

                val result = provider.listFiles("builtin:downloads", "", 0, 200)

                assertEquals(0, result.totalCount)
                coVerify { mockMediaStoreFileOperations.listFiles("builtin:downloads", "", 0, 200) }
            }

        @Test
        fun `listFiles routes to SAF for non-builtin ID`() =
            runTest {
                setupAuthorizedLocation("loc1")
                val mockDir = mockk<DocumentFile>()
                setupRootDocument(mockDir)
                every { mockDir.isDirectory } returns true
                every { mockDir.listFiles() } returns emptyArray()

                provider.listFiles("loc1", "", 0, 10)

                coVerify(exactly = 0) { mockMediaStoreFileOperations.listFiles(any(), any(), any(), any()) }
            }

        @Test
        fun `readFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.readFile("builtin:downloads", "test.txt", 1, 200)
                } returns FileReadResult(content = "hello", totalLines = 1, hasMore = false, startLine = 1, endLine = 1)

                val result = provider.readFile("builtin:downloads", "test.txt", 1, 200)

                assertEquals("hello", result.content)
                coVerify { mockMediaStoreFileOperations.readFile("builtin:downloads", "test.txt", 1, 200) }
            }

        @Test
        fun `writeFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.writeFile("builtin:downloads", "test.txt", "content")
                } returns Unit

                provider.writeFile("builtin:downloads", "test.txt", "content")

                coVerify { mockMediaStoreFileOperations.writeFile("builtin:downloads", "test.txt", "content") }
            }

        @Test
        fun `appendFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.appendFile("builtin:downloads", "test.txt", "more")
                } returns Unit

                provider.appendFile("builtin:downloads", "test.txt", "more")

                coVerify { mockMediaStoreFileOperations.appendFile("builtin:downloads", "test.txt", "more") }
            }

        @Test
        fun `replaceInFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.replaceInFile("builtin:downloads", "test.txt", "old", "new", false)
                } returns FileReplaceResult(1)

                val result = provider.replaceInFile("builtin:downloads", "test.txt", "old", "new", false)

                assertEquals(1, result.replacementCount)
                coVerify {
                    mockMediaStoreFileOperations.replaceInFile("builtin:downloads", "test.txt", "old", "new", false)
                }
            }

        @Test
        fun `downloadFromUrl routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.downloadFromUrl(
                        "builtin:downloads",
                        "file.txt",
                        "https://example.com/f",
                    )
                } returns 1234L

                val result =
                    provider.downloadFromUrl(
                        "builtin:downloads",
                        "file.txt",
                        "https://example.com/f",
                    )

                assertEquals(1234L, result)
                coVerify {
                    mockMediaStoreFileOperations.downloadFromUrl(
                        "builtin:downloads",
                        "file.txt",
                        "https://example.com/f",
                    )
                }
            }

        @Test
        fun `deleteFile routes to MediaStore for builtin ID`() =
            runTest {
                coEvery {
                    mockMediaStoreFileOperations.deleteFile("builtin:downloads", "test.txt")
                } returns Unit

                provider.deleteFile("builtin:downloads", "test.txt")

                coVerify { mockMediaStoreFileOperations.deleteFile("builtin:downloads", "test.txt") }
            }

        @Test
        fun `createFileUri routes to MediaStore for builtin ID`() =
            runTest {
                val mockUri = mockk<Uri>()
                coEvery {
                    mockMediaStoreFileOperations.createFileUri("builtin:downloads", "test.jpg", "image/jpeg")
                } returns mockUri

                val result = provider.createFileUri("builtin:downloads", "test.jpg", "image/jpeg")

                assertEquals(mockUri, result)
                coVerify {
                    mockMediaStoreFileOperations.createFileUri("builtin:downloads", "test.jpg", "image/jpeg")
                }
            }
    }

    @Nested
    @DisplayName("path traversal validation (non-builtin locations)")
    inner class PathTraversalValidation {
        // The guard runs before authorization and resolution, so a bad path is rejected
        // for every entry point regardless of mock setup. "loc1" is a non-builtin location.

        @Test
        fun `listFiles rejects parent traversal segment`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        provider.listFiles("loc1", "../secrets", 0, 10)
                    }
                assertTrue(exception.message!!.contains(".."))
            }

        @Test
        fun `readFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.readFile("loc1", "sub/../../etc/hosts", 1, 10)
                }
            }

        @Test
        fun `readFileBytes rejects absolute path`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        provider.readFileBytes("loc1", "/etc/hosts", TEN_MB)
                    }
                assertTrue(exception.message!!.contains("absolute"))
            }

        @Test
        fun `writeFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.writeFile("loc1", "../escape.txt", "data")
                }
            }

        @Test
        fun `appendFile rejects current-directory segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.appendFile("loc1", "./relative.txt", "data")
                }
            }

        @Test
        fun `replaceInFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.replaceInFile("loc1", "../file.txt", "a", "b", false)
                }
            }

        @Test
        fun `downloadFromUrl rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.downloadFromUrl("loc1", "../evil.bin", "https://example.com/f")
                }
            }

        @Test
        fun `deleteFile rejects parent traversal segment`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    provider.deleteFile("loc1", "../../etc/passwd")
                }
            }

        @Test
        fun `createFileUri rejects control-character segment`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        provider.createFileUri("loc1", "bad\u0000name.txt", "text/plain")
                    }
                assertTrue(exception.message!!.contains("control"))
            }
    }

    companion object {
        private const val BYTES_PER_MB = 1024 * 1024
        private const val DEFAULT_FILE_SIZE_LIMIT_MB = 50
        private const val FILE_SIZE_EXCEEDING_LIMIT = 100_000_000L
        private const val TEN_MB = 10L * 1024 * 1024
        private const val LIST_ENTRIES_ABOVE_MAX = 210
        private const val LINES_ABOVE_MAX = 210
        private const val REQUESTED_LIMIT_ABOVE_MAX = 300
        private const val IO_THREAD = "saf-io-test"
        private const val PAGE_LIMIT = 10
    }
}
