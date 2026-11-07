package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellLaunch
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellScriptBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ShellRunParams(
    val launch: ShellLaunch,
    val elevated: Boolean,
    val justification: String,
)

internal object ShellToolParams {
    const val MAX_SCRIPT_BYTES = 100_000
    const val MAX_JUSTIFICATION_CHARS = 1_000

    fun run(
        arguments: JsonObject?,
        config: ShellConfig,
        background: Boolean,
    ): ShellRunParams {
        val elevated = McpToolUtils.optionalBoolean(arguments, "elevated", false)
        val launch =
            ShellLaunch(
                command = text(arguments, "command"),
                workdir = workdir(arguments),
                envs = envs(arguments),
                timeoutMinutes = timeoutMinutes(arguments, config, background),
            )
        checkScriptSize(launch)
        return ShellRunParams(launch, elevated, justification(arguments, elevated))
    }

    fun waitSeconds(
        arguments: JsonObject?,
        config: ShellConfig,
    ): Int {
        val max = config.limit(ShellLimit.FOREGROUND_WAIT_SECONDS)
        val value = McpToolUtils.optionalInt(arguments, "wait_seconds", 0)
        if (value !in 0..max) {
            throw McpToolException.InvalidParams("Parameter 'wait_seconds' must be between 0 and $max, got: $value")
        }
        return value
    }

    private fun text(
        arguments: JsonObject?,
        name: String,
    ): String {
        val value = McpToolUtils.requireString(arguments, name)
        if (value.isBlank() || ShellScriptBuilder.hasNul(value)) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a non-empty string without NUL characters",
            )
        }
        return value
    }

    private fun workdir(arguments: JsonObject?): String {
        val workdir = text(arguments, "workdir")
        if (!workdir.startsWith("/")) {
            throw McpToolException.InvalidParams("Parameter 'workdir' must be an absolute path, got: '$workdir'")
        }
        return workdir
    }

    private fun justification(
        arguments: JsonObject?,
        elevated: Boolean,
    ): String {
        val value = McpToolUtils.optionalString(arguments, "justification", "").trim()
        if (elevated && value.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'justification' is required when 'elevated' is true")
        }
        if (value.length > MAX_JUSTIFICATION_CHARS) {
            throw McpToolException.InvalidParams(
                "Parameter 'justification' must be at most $MAX_JUSTIFICATION_CHARS characters, got: ${value.length}",
            )
        }
        return value
    }

    private fun envs(arguments: JsonObject?): Map<String, String> {
        val element = arguments?.get("envs") ?: return emptyMap()
        val envs =
            element as? JsonObject
                ?: throw McpToolException.InvalidParams("Parameter 'envs' must be an object whose values are strings")
        return envs.mapValues { (name, value) -> envValue(name, value) }
    }

    private fun envValue(
        name: String,
        value: JsonElement,
    ): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null || !ShellScriptBuilder.isValidEnvName(name) || ShellScriptBuilder.hasNul(text)) {
            throw McpToolException.InvalidParams(
                "Parameter 'envs' entry '$name' needs a name of letters, digits and underscores not starting with " +
                    "a digit, and a string value without NUL characters",
            )
        }
        return text
    }

    private fun timeoutMinutes(
        arguments: JsonObject?,
        config: ShellConfig,
        background: Boolean,
    ): Int {
        val max = config.limit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES)
        val default = config.backgroundTimeoutDefaultMinutes
        val value = if (background) McpToolUtils.optionalInt(arguments, "timeout_minutes", default) else default
        if (value !in 1..max) {
            throw McpToolException.InvalidParams(
                "Parameter 'timeout_minutes' must be between 1 and $max, got: $value",
            )
        }
        return value
    }

    private fun checkScriptSize(launch: ShellLaunch) {
        val bytes =
            ShellScriptBuilder()
                .build(launch.command, launch.workdir, launch.envs)
                .toByteArray(Charsets.UTF_8)
                .size
        if (bytes > MAX_SCRIPT_BYTES) {
            throw McpToolException.InvalidParams(
                "Parameters 'command', 'workdir' and 'envs' are too long together: $bytes bytes once quoted for " +
                    "the shell, the limit is $MAX_SCRIPT_BYTES",
            )
        }
    }
}
