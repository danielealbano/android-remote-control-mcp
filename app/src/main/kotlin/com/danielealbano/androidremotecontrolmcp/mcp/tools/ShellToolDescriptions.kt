package com.danielealbano.androidremotecontrolmcp.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object ShellToolNames {
    const val EXEC = "shell_exec"
    const val START = "shell_start"
    const val OUTPUT = "shell_output"
    const val KILL = "shell_kill"
    const val LIST = "shell_list"
}

private fun commonText(outputTool: String): String =
    "Runs with /system/bin/sh (no terminal; stdin is closed). Output is stdout and stderr combined, returned in " +
        "pages: when has_more_output is true, call $outputTool with offset = next_offset. 'workdir' is required on " +
        "every call; nothing is remembered between calls. 'envs' sets environment variables for this command only. " +
        "When the command exits, anything it left running in its process group is stopped. If the client cancels " +
        "the call before it returns, the command is killed. "

private fun startTools(prefix: String): String = "$prefix${ShellToolNames.EXEC} or $prefix${ShellToolNames.START}"

internal object ShellToolDescriptions {
    private const val ELEVATION =
        "Commands run as the app's own Android user. With 'elevated': true the command runs as the adb shell " +
            "user through Shizuku (as root when Shizuku runs as root) or, when Shizuku is not usable, as root " +
            "through su. This works only if the user allowed elevated commands in the app, may need the user's " +
            "approval on the phone, and needs a 'justification', which is shown to the user and logged. A refused " +
            "command returns an error saying why. "

    fun run(
        background: Boolean,
        scratchPath: String?,
        prefix: String,
    ): String {
        val outputTool = "$prefix${ShellToolNames.OUTPUT}"
        val killTool = "$prefix${ShellToolNames.KILL}"
        val intro =
            if (background) {
                "Starts a shell command in the background and returns its process_id without waiting for it to " +
                    "end (an elevated command that needs the user's approval first waits for that answer, at most " +
                    "the foreground limit); read its output and exit code with $outputTool, stop it with " +
                    "$killTool. It is stopped after 'timeout_minutes' (default and maximum are set in the app's " +
                    "Shell settings) or when the server stops. "
            } else {
                "Runs a shell command and waits for it to end, at most the foreground limit set in the app (90 " +
                    "seconds by default; waiting for the user's approval counts toward it). A command still running " +
                    "at the limit keeps running in the background: continue with $outputTool and the returned " +
                    "process_id, stop it with $killTool. It is stopped after the default background timeout set in " +
                    "the app's Shell settings (counted from its start) or when the server stops. "
            }
        return intro + commonText(outputTool) + ELEVATION + scratch(scratchPath)
    }

    fun output(prefix: String): String =
        "Returns the output of a process started by ${startTools(prefix)} from 'offset' (default 0), at most " +
            "the per-call limit set in the app, with its state and exit code. 'wait_seconds' (0 up to the " +
            "foreground limit, default 0) first waits for new output or for the process to end. A process keeps " +
            "the start and the end of its output when it exceeds the buffer set in the app; output_dropped_bytes " +
            "tells how much of the middle was skipped. Processes stay listed until the server stops or until they " +
            "fall out of the finished-process limit."

    fun kill(prefix: String): String =
        "Stops a process started by ${startTools(prefix)} together with every process in its process group " +
            "(SIGKILL), waits up to 2 seconds for it to end and returns its state."

    fun list(): String =
        "Lists the running and recently finished shell processes with state, exit code, command (first 200 " +
            "characters), backend (app, shizuku or su), uid and output size."

    fun runSchema(background: Boolean): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    property("command", "string", "The command, run with /system/bin/sh -c.")
                    property("workdir", "string", "Absolute path of the working directory (required on every call).")
                    putJsonObject("envs") {
                        put("type", "object")
                        putJsonObject("additionalProperties") { put("type", "string") }
                        put("description", "Environment variables for this command only, as name: value strings.")
                    }
                    property("elevated", "boolean", "Run the command elevated through Shizuku or su (default false).")
                    property(
                        "justification",
                        "string",
                        "Why elevation is needed; required when elevated is true, shown to the user and logged.",
                    )
                    if (background) {
                        property(
                            "timeout_minutes",
                            "integer",
                            "Minutes after which the command is stopped (default and maximum set in the app).",
                        )
                    }
                },
            required = listOf("command", "workdir"),
        )

    fun outputSchema(prefix: String): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    property("process_id", "integer", "The process_id returned by ${startTools(prefix)}.")
                    property("offset", "integer", "Byte offset to read from (default 0); continue with next_offset.")
                    property("wait_seconds", "integer", "Seconds to wait for new output or the end first (default 0).")
                },
            required = listOf("process_id"),
        )

    fun killSchema(): ToolSchema =
        ToolSchema(
            properties = buildJsonObject { property("process_id", "integer", "The process_id to stop.") },
            required = listOf("process_id"),
        )

    fun emptySchema(): ToolSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList())

    private fun scratch(path: String?): String =
        if (path == null) {
            "No scratch folder is available (the device has no external storage)."
        } else {
            "Scratch folder, readable and writable by normal and elevated commands and emptied when the server " +
                "starts (entries the app cannot delete stay): $path. It is mounted noexec: run scripts with " +
                "'sh script.sh'; to run a binary, copy it to /data/local/tmp with an elevated command and run it " +
                "from there."
        }

    private fun JsonObjectBuilder.property(
        name: String,
        type: String,
        description: String,
    ) {
        putJsonObject(name) {
            put("type", type)
            put("description", description)
        }
    }
}
