package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.delay

/**
 * Keeps the notification in line with the status page while a free E2E tunnel session runs. It only touches the
 * notification when the answer changes, and removes it when the session ends. The page itself is asked at most once per
 * [ArcTunnelStatusPage.CHECK_INTERVAL_MS]: refreshing more often only picks up an answer the Server screen got first.
 */
class ArcTunnelServiceStatusWatcher(
    private val status: ArcTunnelServiceStatus,
    private val alerts: ArcTunnelServiceAlerts,
) {
    suspend fun run() {
        var shown: ArcTunnelServiceProblem? = null
        try {
            while (true) {
                val problem = status.currentProblem()
                if (problem != shown) {
                    if (problem == null) alerts.clear() else alerts.show(problem)
                    shown = problem
                }
                delay(REFRESH_MS)
            }
        } finally {
            alerts.clear()
        }
    }

    private companion object {
        const val REFRESH_MS = 300_000L
    }
}
