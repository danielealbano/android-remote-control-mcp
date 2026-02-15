@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// list_storage_locations
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_storage_locations`.
 *
 * Lists available storage locations on the device with their authorization status.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "[{\"id\":\"...\", ...}]" }] }`
 * **Errors**:
 *   - ActionFailed if listing storage locations fails
 */
class ListStorageLocationsHandler
    @Inject
    constructor(
        private val storageLocationProvider: StorageLocationProvider,
    ) {
        @Suppress("UnusedParameter", "TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locations = storageLocationProvider.getAvailableLocations()
                val jsonArray =
                    buildJsonArray {
                        for (location in locations) {
                            add(
                                buildJsonObject {
                                    put("id", location.id)
                                    put("name", location.name)
                                    put("provider", location.providerName)
                                    put("authorized", location.isAuthorized)
                                    if (location.availableBytes != null) {
                                        put("available_bytes", location.availableBytes)
                                    } else {
                                        put("available_bytes", JsonNull)
                                    }
                                },
                            )
                        }
                    }
                McpToolUtils.textResult(Json.encodeToString(jsonArray))
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to list storage locations: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Lists available storage locations on the device with their authorization status. " +
                        "Locations must be authorized in the app settings before file operations " +
                        "can be performed on them.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_storage_locations"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// list_files
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_files`.
 *
 * Lists files and directories in an authorized storage location with pagination.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "offset": 0, "limit": 200 }`
 * **Output**: `{ "content": [{ "type": "text", "text": "{\"files\":[...], ...}" }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if listing files fails
 */
class ListFilesHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.optionalString(arguments, "path", "")
                val offset = McpToolUtils.optionalInt(arguments, "offset", 0)
                val limit = McpToolUtils.optionalInt(arguments, "limit", FileOperationProvider.MAX_LIST_ENTRIES)

                val result = fileOperationProvider.listFiles(locationId, path, offset, limit)

                val resultJson =
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for (file in result.files) {
                                    add(
                                        buildJsonObject {
                                            put("name", file.name)
                                            put("path", file.path)
                                            put("is_directory", file.isDirectory)
                                            put("size", file.size)
                                            if (file.lastModified != null) {
                                                put("last_modified", file.lastModified)
                                            } else {
                                                put("last_modified", JsonNull)
                                            }
                                            if (file.mimeType != null) {
                                                put("mime_type", file.mimeType)
                                            } else {
                                                put("mime_type", JsonNull)
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put("total_count", result.totalCount)
                        put("has_more", result.hasMore)
                    }

                McpToolUtils.textResult(Json.encodeToString(resultJson))
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to list files: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Lists files and directories in a storage location. The location must be authorized. " +
                        "Returns name, path, type, size, last_modified, and mime_type for each entry.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path within the location (empty for root)")
                                    put("default", "")
                                }
                                putJsonObject("offset") {
                                    put("type", "integer")
                                    put("description", "Number of entries to skip (0-based)")
                                    put("default", 0)
                                }
                                putJsonObject("limit") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Maximum number of entries to return " +
                                            "(max ${FileOperationProvider.MAX_LIST_ENTRIES})",
                                    )
                                    put("default", FileOperationProvider.MAX_LIST_ENTRIES)
                                }
                            },
                        required = listOf("location_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_files"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// read_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `read_file`.
 *
 * Reads a text file from an authorized storage location with line-based pagination.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "offset": 1, "limit": 200 }`
 * **Output**: `{ "content": [{ "type": "text", "text": "1| line content\n2| ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if reading the file fails
 */
class ReadFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val offset = McpToolUtils.optionalInt(arguments, "offset", 1)
                val limit = McpToolUtils.optionalInt(arguments, "limit", FileOperationProvider.MAX_READ_LINES)

                val result = fileOperationProvider.readFile(locationId, path, offset, limit)

                val lines = result.content.split("\n")
                val numberedLines =
                    lines.mapIndexed { index, line ->
                        "${result.startLine + index}| $line"
                    }

                val text =
                    buildString {
                        append(numberedLines.joinToString("\n"))
                        if (result.hasMore) {
                            append(
                                "\n--- More lines available. Use offset=${result.endLine + 1} " +
                                    "to continue reading. Total lines: ${result.totalLines} ---",
                            )
                        }
                    }

                McpToolUtils.textResult(text)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to read file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Reads a text file from an authorized storage location with line-based pagination. " +
                        "Returns content with line numbers. Maximum ${FileOperationProvider.MAX_READ_LINES} " +
                        "lines per call.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("offset") {
                                    put("type", "integer")
                                    put("description", "1-based line number to start reading from")
                                    put("default", 1)
                                }
                                putJsonObject("limit") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Maximum number of lines to return " +
                                            "(max ${FileOperationProvider.MAX_READ_LINES})",
                                    )
                                    put("default", FileOperationProvider.MAX_READ_LINES)
                                }
                            },
                        required = listOf("location_id", "path"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "read_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// write_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `write_file`.
 *
 * Writes text content to a file in an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "content": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "File written successfully: ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if writing the file fails
 */
class WriteFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val content = McpToolUtils.requireString(arguments, "content")

                fileOperationProvider.writeFile(locationId, path, content)

                McpToolUtils.textResult("File written successfully: $locationId/$path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to write file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Writes text content to a file in an authorized storage location. Creates the file if it " +
                        "doesn't exist. Creates parent directories automatically. Overwrites existing content. " +
                        "Pass empty content to create an empty file.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "The text content to write")
                                }
                            },
                        required = listOf("location_id", "path", "content"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "write_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// append_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `append_file`.
 *
 * Appends text content to an existing file in an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "content": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Content appended successfully to: ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if appending fails or provider does not support append mode
 */
class AppendFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val content = McpToolUtils.requireString(arguments, "content")

                fileOperationProvider.appendFile(locationId, path, content)

                McpToolUtils.textResult("Content appended successfully to: $locationId/$path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to append to file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Appends text content to an existing file in an authorized storage location. If the " +
                        "storage provider does not support append mode, an error is returned with a hint " +
                        "to use write_file instead.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "The text content to append")
                                }
                            },
                        required = listOf("location_id", "path", "content"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "append_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// file_replace
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `file_replace`.
 *
 * Performs literal string replacement in a text file in an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "old_string": "...",
 *   "new_string": "...", "replace_all": false }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Replaced N occurrence(s) ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if replacement fails
 */
class FileReplaceHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val oldString = McpToolUtils.requireString(arguments, "old_string")
                val newString = McpToolUtils.requireString(arguments, "new_string")
                val replaceAll = McpToolUtils.optionalBoolean(arguments, "replace_all", false)

                val result = fileOperationProvider.replaceInFile(locationId, path, oldString, newString, replaceAll)

                McpToolUtils.textResult("Replaced ${result.replacementCount} occurrence(s) of the specified string.")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to replace in file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Performs literal string replacement in a text file in an authorized storage location. " +
                        "Reads the file, performs replacement, and writes back. Uses advisory file locking " +
                        "when supported by the provider.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                                putJsonObject("old_string") {
                                    put("type", "string")
                                    put("description", "The literal string to find")
                                }
                                putJsonObject("new_string") {
                                    put("type", "string")
                                    put("description", "The replacement string")
                                }
                                putJsonObject("replace_all") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "If true, replace all occurrences; otherwise replace only the first",
                                    )
                                    put("default", false)
                                }
                            },
                        required = listOf("location_id", "path", "old_string", "new_string"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "file_replace"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// download_from_url
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `download_from_url`.
 *
 * Downloads a file from a URL and saves it to an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "...", "url": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "File downloaded successfully. ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if download or save fails
 */
class DownloadFromUrlHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")
                val url = McpToolUtils.requireString(arguments, "url")

                val downloadedBytes = fileOperationProvider.downloadFromUrl(locationId, path, url)

                McpToolUtils.textResult(
                    "File downloaded successfully. Size: $downloadedBytes bytes. Saved to: $locationId/$path",
                )
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to download file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Downloads a file from a URL and saves it to an authorized storage location. Creates " +
                        "the file and parent directories if they don't exist. Overwrites existing content. " +
                        "Subject to the configured file size limit and download timeout. HTTP downloads and " +
                        "unverified HTTPS certificates must be explicitly allowed in the app settings.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path for the destination file")
                                }
                                putJsonObject("url") {
                                    put("type", "string")
                                    put("description", "The URL to download from")
                                }
                            },
                        required = listOf("location_id", "path", "url"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "download_from_url"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// delete_file
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `delete_file`.
 *
 * Deletes a single file from an authorized storage location.
 *
 * **Input**: `{ "location_id": "...", "path": "..." }`
 * **Output**: `{ "content": [{ "type": "text", "text": "File deleted successfully: ..." }] }`
 * **Errors**:
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if deletion fails
 */
class DeleteFileHandler
    @Inject
    constructor(
        private val fileOperationProvider: FileOperationProvider,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            try {
                val locationId = McpToolUtils.requireString(arguments, "location_id")
                val path = McpToolUtils.requireString(arguments, "path")

                fileOperationProvider.deleteFile(locationId, path)

                McpToolUtils.textResult("File deleted successfully: $locationId/$path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to delete file: ${e.message ?: "Unknown error"}",
                )
            }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Deletes a single file from an authorized storage location. Cannot delete directories.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("location_id") {
                                    put("type", "string")
                                    put("description", "The authorized storage location identifier")
                                }
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Relative path to the file")
                                }
                            },
                        required = listOf("location_id", "path"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "delete_file"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all file operation tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerFileTools(
    server: Server,
    storageLocationProvider: StorageLocationProvider,
    fileOperationProvider: FileOperationProvider,
    toolNamePrefix: String,
) {
    ListStorageLocationsHandler(storageLocationProvider).register(server, toolNamePrefix)
    ListFilesHandler(fileOperationProvider).register(server, toolNamePrefix)
    ReadFileHandler(fileOperationProvider).register(server, toolNamePrefix)
    WriteFileHandler(fileOperationProvider).register(server, toolNamePrefix)
    AppendFileHandler(fileOperationProvider).register(server, toolNamePrefix)
    FileReplaceHandler(fileOperationProvider).register(server, toolNamePrefix)
    DownloadFromUrlHandler(fileOperationProvider).register(server, toolNamePrefix)
    DeleteFileHandler(fileOperationProvider).register(server, toolNamePrefix)
}
