package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalNotifier
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationVerdict
import javax.inject.Inject

private const val MILLIS_PER_SECOND = 1000L
private const val REFUSED_PREFIX = "Refused: "
private const val DENIED_TEXT = "the user denied this elevated command."
private const val TIMED_OUT_TEXT = "the user did not answer the approval request within"
private const val CANCELLED_TEXT = "the MCP server is stopping, so the approval was cancelled."

data class ShellAuthorizationRequest(
    val command: String,
    val justification: String,
    val elevated: Boolean,
)

interface ShellCommandAuthorizer {
    suspend fun authorize(
        request: ShellAuthorizationRequest,
        config: ShellConfig,
        budgetMs: Long,
    ): ElevationGrant?
}

class ShellCommandAuthorizerImpl
    @Inject
    constructor(
        private val approvals: ElevationApprovalCoordinator,
        private val notifier: ElevationApprovalNotifier,
        private val serverLog: ServerLogRepository,
    ) : ShellCommandAuthorizer {
        override suspend fun authorize(
            request: ShellAuthorizationRequest,
            config: ShellConfig,
            budgetMs: Long,
        ): ElevationGrant? {
            CommandPatterns.firstBlocklistMatch(config.blocklist, request.command)?.let { pattern ->
                refuse(request, "${REFUSED_PREFIX}the command matches the shell blocklist pattern '$pattern'.")
            }
            return if (request.elevated) authorizeElevation(request, config, budgetMs) else null
        }

        private suspend fun authorizeElevation(
            request: ShellAuthorizationRequest,
            config: ShellConfig,
            budgetMs: Long,
        ): ElevationGrant {
            if (!config.elevationEnabled) {
                refuse(request, "${REFUSED_PREFIX}elevated commands are disabled in the app's Shell settings.")
            }
            val allowlisted = CommandPatterns.firstAllowlistMatch(config.allowlist, request.command) != null
            return when {
                config.autoAllowElevation -> ElevationGrant.AUTO_ALLOWED
                allowlisted -> ElevationGrant.ALLOWLISTED
                else -> askUser(request, config, budgetMs)
            }
        }

        private suspend fun askUser(
            request: ShellAuthorizationRequest,
            config: ShellConfig,
            budgetMs: Long,
        ): ElevationGrant {
            if (!notifier.canNotify()) {
                refuse(
                    request,
                    "${REFUSED_PREFIX}the app cannot ask the user to approve this elevated command because its " +
                        "notifications are off.",
                )
            }
            val timeoutMs = minOf(config.limit(ShellLimit.APPROVAL_TIMEOUT_SECONDS) * MILLIS_PER_SECOND, budgetMs)
            return when (val verdict = approvals.request(request.command, request.justification, timeoutMs)) {
                ElevationVerdict.APPROVED -> ElevationGrant.APPROVED
                else -> refuse(request, refusalFor(verdict, timeoutMs))
            }
        }

        private fun refusalFor(
            verdict: ElevationVerdict,
            timeoutMs: Long,
        ): String =
            when (verdict) {
                ElevationVerdict.TIMED_OUT -> "$REFUSED_PREFIX$TIMED_OUT_TEXT ${timeoutMs / MILLIS_PER_SECOND} seconds."
                ElevationVerdict.CANCELLED -> "$REFUSED_PREFIX$CANCELLED_TEXT"
                else -> "$REFUSED_PREFIX$DENIED_TEXT"
            }

        private fun refuse(
            request: ShellAuthorizationRequest,
            message: String,
        ): Nothing {
            if (request.elevated) {
                serverLog.log(
                    ServerLogEntry.Type.SHELL,
                    "Elevated command refused (${message.removePrefix(REFUSED_PREFIX)}) | justification: " +
                        "${request.justification} | command: ${request.command}",
                )
            }
            throw McpToolException.PermissionDenied(message)
        }
    }
