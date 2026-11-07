package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.shell.BYTES_PER_KIB
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellAuthorizationRequest
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellCommandAuthorizer
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellElevation
import com.danielealbano.androidremotecontrolmcp.services.shell.ShellProcessManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

private const val MILLIS_PER_SECOND = 1000L
private const val KILL_SETTLE_MS = 2_000L

class ShellToolContext(
    val processManager: ShellProcessManager,
    val authorizer: ShellCommandAuthorizer,
    val settingsRepository: SettingsRepository,
    val scratchPath: String?,
    val timeSource: TimeSource = TimeSource.Monotonic,
)

private fun perCallBytes(config: ShellConfig): Int = config.limit(ShellLimit.OUTPUT_PER_CALL_KIB) * BYTES_PER_KIB

class ShellRunHandler(
    private val context: ShellToolContext,
    private val background: Boolean,
) {
    private val toolName = if (background) ShellToolNames.START else ShellToolNames.EXEC

    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val mark = context.timeSource.markNow()
        val config = context.settingsRepository.getServerConfig().shellConfig
        val params = ShellToolParams.run(arguments, config, background)
        val request = ShellAuthorizationRequest(params.launch.command, params.justification, params.elevated)
        val budgetMs = config.limit(ShellLimit.FOREGROUND_WAIT_SECONDS) * MILLIS_PER_SECOND
        val grant = context.authorizer.authorize(request, config, budgetMs)
        val launch = params.launch.copy(elevation = grant?.let { ShellElevation(params.justification, it) })
        val waitMs = if (background) 0L else budgetMs - mark.elapsedNow().inWholeMilliseconds
        val page = context.processManager.startAndAwait(launch, config, waitMs, perCallBytes(config))
        return McpToolUtils.untrustedTextResult(ShellToolJson.process(page).toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = toolName,
            name = "$toolNamePrefix$toolName",
            description = ShellToolDescriptions.run(background, context.scratchPath, toolNamePrefix),
            inputSchema = ShellToolDescriptions.runSchema(background),
        ) { request -> execute(request.arguments) }
    }
}

class ShellOutputHandler(
    private val context: ShellToolContext,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val config = context.settingsRepository.getServerConfig().shellConfig
        val id = McpToolUtils.requireInt(arguments, "process_id")
        val offset = McpToolUtils.optionalLong(arguments, "offset", 0L)
        val waitMs = ShellToolParams.waitSeconds(arguments, config) * MILLIS_PER_SECOND
        val page = context.processManager.page(id, offset, waitMs, perCallBytes(config))
        return McpToolUtils.untrustedTextResult(ShellToolJson.process(page).toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = ShellToolNames.OUTPUT,
            name = "$toolNamePrefix${ShellToolNames.OUTPUT}",
            description = ShellToolDescriptions.output(toolNamePrefix),
            inputSchema = ShellToolDescriptions.outputSchema(toolNamePrefix),
        ) { request -> execute(request.arguments) }
    }
}

class ShellKillHandler(
    private val context: ShellToolContext,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val id = McpToolUtils.requireInt(arguments, "process_id")
        val snapshot = context.processManager.killAndAwait(id, KILL_SETTLE_MS)
        return McpToolUtils.untrustedTextResult(ShellToolJson.summary(snapshot).toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = ShellToolNames.KILL,
            name = "$toolNamePrefix${ShellToolNames.KILL}",
            description = ShellToolDescriptions.kill(toolNamePrefix),
            inputSchema = ShellToolDescriptions.killSchema(),
        ) { request -> execute(request.arguments) }
    }
}

class ShellListHandler(
    private val context: ShellToolContext,
) {
    fun execute(): CallToolResult {
        val processes = context.processManager.list()
        return McpToolUtils.untrustedTextResult(ShellToolJson.list(processes).toString())
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = ShellToolNames.LIST,
            name = "$toolNamePrefix${ShellToolNames.LIST}",
            description = ShellToolDescriptions.list(),
            inputSchema = ShellToolDescriptions.emptySchema(),
        ) { _ -> execute() }
    }
}

fun registerShellTools(
    registrar: LoggedToolRegistrar,
    context: ShellToolContext,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(ShellToolNames.EXEC)) {
        ShellRunHandler(context, background = false).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ShellToolNames.START)) {
        ShellRunHandler(context, background = true).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ShellToolNames.OUTPUT)) ShellOutputHandler(context).register(registrar, toolNamePrefix)
    if (perms.isToolEnabled(ShellToolNames.KILL)) ShellKillHandler(context).register(registrar, toolNamePrefix)
    if (perms.isToolEnabled(ShellToolNames.LIST)) ShellListHandler(context).register(registrar, toolNamePrefix)
}
