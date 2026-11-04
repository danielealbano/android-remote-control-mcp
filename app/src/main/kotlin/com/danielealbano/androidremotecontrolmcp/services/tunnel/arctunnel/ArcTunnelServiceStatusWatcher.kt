package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.delay

/**
 * Keeps the notification in line with the status page while a free E2E tunnel session runs. It only touches the
 * notification when the answer changes or the last post could not be shown, and removes it when the session ends. The
 * page itself is asked at most once per [ArcTunnelStatusPage.CHECK_INTERVAL_MS]: refreshing more often only picks up an
 * answer the Server screen got first.
 */
class ArcTunnelServiceStatusWatcher(
    private val status: ArcTunnelServiceStatus,
    private val alerts: ArcTunnelServiceAlerts,
) {
    suspend fun run() {
        var shown: ArcTunnelServiceProblem? = null
        try {
            while (true) {
                shown = update(shown, status.currentProblem())
                delay(REFRESH_MS)
            }
        } finally {
            alerts.clear()
        }
    }

    /** What the notification shows now. One that could not be shown is retried, as the user may allow it later. */
    private fun update(
        shown: ArcTunnelServiceProblem?,
        problem: ArcTunnelServiceProblem?,
    ): ArcTunnelServiceProblem? =
        when {
            problem == shown -> {
                shown
            }

            problem == null -> {
                alerts.clear()
                null
            }

            alerts.show(problem) -> {
                problem
            }

            else -> {
                shown
            }
        }

    private companion object {
        const val REFRESH_MS = 300_000L
    }
}
