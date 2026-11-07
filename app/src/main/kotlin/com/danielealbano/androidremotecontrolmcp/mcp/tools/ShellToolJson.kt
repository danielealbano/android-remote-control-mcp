package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessPage
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object ShellToolJson {
    private const val LIST_COMMAND_CHARS = 200

    fun process(page: ShellProcessPage): JsonObject =
        buildJsonObject {
            putState(page.snapshot)
            put("output", page.chunk.text)
            put("output_offset", page.chunk.startOffset)
            put("next_offset", page.chunk.nextOffset)
            put("output_total_bytes", page.chunk.totalBytes)
            put("output_dropped_bytes", page.chunk.droppedBytes)
            put("has_more_output", page.chunk.nextOffset < page.chunk.totalBytes)
        }

    fun summary(snapshot: ShellProcessSnapshot): JsonObject =
        buildJsonObject {
            putState(snapshot)
            put("command", snapshot.command.take(LIST_COMMAND_CHARS))
            put("elevated", snapshot.elevated)
            put("started_at_ms", snapshot.startedAtMs)
            put("ended_at_ms", snapshot.endedAtMs)
            put("output_total_bytes", snapshot.totalBytes)
        }

    fun list(snapshots: List<ShellProcessSnapshot>): JsonObject =
        buildJsonObject {
            putJsonArray("processes") { snapshots.forEach { add(summary(it)) } }
        }

    private fun JsonObjectBuilder.putState(snapshot: ShellProcessSnapshot) {
        put("process_id", snapshot.id)
        put("state", snapshot.state.wireName)
        put("exit_code", snapshot.exitCode)
        put("backend", snapshot.backend.wireName)
        put("uid", snapshot.uid)
        put("failure", snapshot.failure)
    }
}
