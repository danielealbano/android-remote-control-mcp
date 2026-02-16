package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.storage.FileListResult
import com.danielealbano.androidremotecontrolmcp.services.storage.FileReadResult
import com.danielealbano.androidremotecontrolmcp.services.storage.FileReplaceResult
import io.mockk.coEvery
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
@DisplayName("File Tools Integration Tests")
class FileToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // list_storage_locations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `list_storage_locations returns available locations`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.storageLocationProvider.getAllLocations() } returns
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Downloads",
                        path = "/",
                        description = "Downloaded files",
                        treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
                        availableBytes = 1024000L,
                        allowWrite = true,
                        allowDelete = false,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_list_storage_locations", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Downloads"))
                assertTrue(text.contains("Downloaded files"))
                assertTrue(text.contains("path"))
                assertFalse(text.contains("authorized"))
                assertFalse(text.contains("provider"))
                assertTrue(text.contains("\"allow_read\":true"))
                assertTrue(text.contains("\"allow_write\":true"))
                assertTrue(text.contains("\"allow_delete\":false"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // list_files
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `list_files with valid location returns file entries`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.listFiles("loc1", "", 0, 200) } returns
                FileListResult(
                    files =
                        listOf(
                            FileInfo(
                                name = "test.txt",
                                path = "loc1/test.txt",
                                isDirectory = false,
                                size = 100L,
                                lastModified = 1000L,
                                mimeType = "text/plain",
                            ),
                        ),
                    totalCount = 1,
                    hasMore = false,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_files",
                        arguments = mapOf("location_id" to "loc1"),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("test.txt"))
            }
        }

    @Test
    fun `list_files with unauthorized location returns permission denied error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.listFiles(any(), any(), any(), any()) } throws
                McpToolException.PermissionDenied("Storage location 'loc1' not found.")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_files",
                        arguments = mapOf("location_id" to "loc1"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("not found"))
            }
        }

    @Test
    fun `list_files with offset and limit returns paginated results`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.listFiles("loc1", "", 5, 10) } returns
                FileListResult(
                    files = emptyList(),
                    totalCount = 20,
                    hasMore = true,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_files",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "offset" to 5,
                                "limit" to 10,
                            ),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("has_more"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // read_file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `read_file returns text content with line numbers`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.readFile("loc1", "file.txt", 1, 200) } returns
                FileReadResult(
                    content = "line1\nline2\nline3",
                    totalLines = 3,
                    hasMore = false,
                    startLine = 1,
                    endLine = 3,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_read_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                            ),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("line1"))
            }
        }

    @Test
    fun `read_file with offset reads from specified line`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.readFile("loc1", "file.txt", 5, 200) } returns
                FileReadResult(
                    content = "line5\nline6",
                    totalLines = 10,
                    hasMore = true,
                    startLine = 5,
                    endLine = 6,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_read_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "offset" to 5,
                            ),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("line5"))
            }
        }

    @Test
    fun `read_file adds hasMore hint when more lines exist`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.readFile("loc1", "file.txt", 5, 200) } returns
                FileReadResult(
                    content = "line5\nline6",
                    totalLines = 10,
                    hasMore = true,
                    startLine = 5,
                    endLine = 6,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_read_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "offset" to 5,
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("More lines available"))
            }
        }

    @Test
    fun `read_file with unauthorized location returns permission denied error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.readFile(any(), any(), any(), any()) } throws
                McpToolException.PermissionDenied("Storage location 'loc1' not found.")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_read_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("not found"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // write_file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `write_file creates file successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.writeFile("loc1", "file.txt", "hello") } returns Unit

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_write_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "content" to "hello",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("written successfully"))
            }
        }

    @Test
    fun `write_file with empty content creates empty file`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.writeFile("loc1", "file.txt", "") } returns Unit

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_write_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "content" to "",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("written successfully"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // append_file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `append_file appends content successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.appendFile("loc1", "file.txt", "more data") } returns Unit

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_append_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "content" to "more data",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("appended successfully"))
            }
        }

    @Test
    fun `append_file returns error with hint when append unsupported`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.appendFile(any(), any(), any()) } throws
                McpToolException.ActionFailed("does not support append mode")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_append_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "content" to "more data",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("append mode"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // file_replace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `file_replace replaces first occurrence`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.replaceInFile("loc1", "file.txt", "old", "new", false)
            } returns FileReplaceResult(1)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_file_replace",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "old_string" to "old",
                                "new_string" to "new",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Replaced 1"))
            }
        }

    @Test
    fun `file_replace with replace_all replaces all occurrences`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.replaceInFile("loc1", "file.txt", "old", "new", true)
            } returns FileReplaceResult(3)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_file_replace",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "old_string" to "old",
                                "new_string" to "new",
                                "replace_all" to true,
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Replaced 3"))
            }
        }

    @Test
    fun `file_replace returns zero count when string not found`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.replaceInFile("loc1", "file.txt", "old", "new", false)
            } returns FileReplaceResult(0)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_file_replace",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "old_string" to "old",
                                "new_string" to "new",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Replaced 0"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // download_from_url
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `download_from_url downloads file successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.downloadFromUrl("loc1", "file.txt", "https://example.com/file.txt")
            } returns 12345L

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_download_from_url",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "url" to "https://example.com/file.txt",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("12345 bytes"))
            }
        }

    @Test
    fun `download_from_url returns error when HTTP not allowed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.downloadFromUrl(any(), any(), any())
            } throws McpToolException.ActionFailed("HTTP downloads are not allowed")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_download_from_url",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "url" to "http://example.com/file.txt",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("HTTP downloads are not allowed"))
            }
        }

    @Test
    fun `download_from_url returns error when Content-Length exceeds size limit`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.downloadFromUrl(any(), any(), any())
            } throws McpToolException.ActionFailed("Content-Length exceeds the configured file size limit")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_download_from_url",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "url" to "https://example.com/large-file.bin",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("exceeds"))
            }
        }

    @Test
    fun `download_from_url returns error when streamed size exceeds size limit`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.downloadFromUrl(any(), any(), any())
            } throws McpToolException.ActionFailed("exceeds the configured file size limit")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_download_from_url",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "url" to "https://example.com/large-file.bin",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("exceeds the configured file size limit"))
            }
        }

    @Test
    fun `download_from_url returns error for invalid URL`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.downloadFromUrl(any(), any(), any())
            } throws McpToolException.ActionFailed("Invalid URL")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_download_from_url",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "url" to "not-a-url",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Invalid URL"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // delete_file
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `delete_file deletes file successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.deleteFile("loc1", "file.txt") } returns Unit

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_delete_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("deleted successfully"))
            }
        }

    @Test
    fun `delete_file returns error for directory`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.deleteFile(any(), any()) } throws
                McpToolException.ActionFailed("Cannot delete a directory")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_delete_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "some-dir",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Cannot delete a directory"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission denial tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `write_file returns error when write not allowed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.writeFile(any(), any(), any()) } throws
                McpToolException.PermissionDenied("Write not allowed")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_write_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "content" to "hello",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Write not allowed"))
            }
        }

    @Test
    fun `append_file returns error when write not allowed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.appendFile(any(), any(), any()) } throws
                McpToolException.PermissionDenied("Write not allowed")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_append_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "content" to "more data",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Write not allowed"))
            }
        }

    @Test
    fun `file_replace returns error when write not allowed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.replaceInFile(any(), any(), any(), any(), any())
            } throws McpToolException.PermissionDenied("Write not allowed")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_file_replace",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "old_string" to "old",
                                "new_string" to "new",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Write not allowed"))
            }
        }

    @Test
    fun `download_from_url returns error when write not allowed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.downloadFromUrl(any(), any(), any())
            } throws McpToolException.PermissionDenied("Write not allowed")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_download_from_url",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                                "url" to "https://example.com/file.txt",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Write not allowed"))
            }
        }

    @Test
    fun `delete_file returns error when delete not allowed`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.fileOperationProvider.deleteFile(any(), any()) } throws
                McpToolException.PermissionDenied("Delete not allowed")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_delete_file",
                        arguments =
                            mapOf(
                                "location_id" to "loc1",
                                "path" to "file.txt",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Delete not allowed"))
            }
        }
}
