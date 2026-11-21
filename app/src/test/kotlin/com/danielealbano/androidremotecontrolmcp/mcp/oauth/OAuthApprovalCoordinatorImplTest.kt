package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.geo.GeoLocation
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthApprovalCoordinatorImpl")
class OAuthApprovalCoordinatorImplTest {
    private fun newCoordinator(serverLog: ServerLogRepository) = OAuthApprovalCoordinatorImpl(serverLog)

    private fun req(
        iconUrl: String? = null,
        clientIp: String? = null,
        clientGeo: GeoLocation? = null,
    ) = ApprovalRequest("Claude", "claude.ai", iconUrl, clientIp, clientGeo)

    @Test
    @DisplayName("createPending appears in pending flow with 2-digit code")
    fun createPendingAppears() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val approval = coordinator.createPending(req(), 0L)
            assertEquals(2, approval.matchCode.length)
            assertTrue(coordinator.observePending().value.any { it.id == approval.id })
        }

    @Test
    @DisplayName("createPending carries the icon URL, the loopback flag and the other-domain host onto the approval")
    fun createPendingCarriesIconUrl() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val approval = coordinator.createPending(req(iconUrl = "https://cdn.example/logo.png"), 0L)
            assertEquals("https://cdn.example/logo.png", approval.iconUrl)
            val request = ApprovalRequest("C", "localhost", null, null, null, loopbackRedirect = true)
            val loopback = coordinator.createPending(request, 0L)
            assertEquals(true, loopback.loopbackRedirect)
            val other = ApprovalRequest("C", "claude.ai", null, null, null, otherDomainHost = "chatgpt.com")
            assertEquals("chatgpt.com", coordinator.createPending(other, 0L).otherDomainHost)
        }

    @Test
    @DisplayName("createPending carries the client IP and geolocation onto the pending approval")
    fun createPendingCarriesIpAndGeo() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val geo = GeoLocation("US", "Mountain View")
            val approval = coordinator.createPending(req(clientIp = "8.8.8.8", clientGeo = geo), 0L)
            assertEquals("8.8.8.8", approval.clientIp)
            assertEquals(geo, approval.clientGeo)
        }

    @Test
    @DisplayName("approve transitions and clears pending")
    fun approveTransitions() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val approval = coordinator.createPending(req(), 0L)
            coordinator.approve(approval.id, 1L)
            assertEquals(ApprovalState.APPROVED, coordinator.stateOf(approval.id, 1L))
            assertTrue(coordinator.observePending().value.none { it.id == approval.id })
        }

    @Test
    @DisplayName("approve after the window has lapsed yields EXPIRED, not APPROVED")
    fun approveAfterExpiryYieldsExpired() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val approval = coordinator.createPending(req(), 0L)
            coordinator.approve(approval.id, OAuthPolicy.APPROVAL_WINDOW_MS + 1)
            assertEquals(ApprovalState.EXPIRED, coordinator.stateOf(approval.id, OAuthPolicy.APPROVAL_WINDOW_MS + 1))
        }

    @Test
    @DisplayName("deny transitions to DENIED")
    fun denyTransitions() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val approval = coordinator.createPending(req(), 0L)
            coordinator.deny(approval.id)
            assertEquals(ApprovalState.DENIED, coordinator.stateOf(approval.id, 1L))
            assertTrue(coordinator.observePending().value.none { it.id == approval.id })
        }

    @Test
    @DisplayName("expiry yields EXPIRED")
    fun expiryYieldsExpired() =
        runTest {
            val coordinator = newCoordinator(RecordingServerLogRepository())
            val approval = coordinator.createPending(req(), 0L)
            assertEquals(ApprovalState.EXPIRED, coordinator.stateOf(approval.id, OAuthPolicy.APPROVAL_WINDOW_MS + 1))
            assertTrue(coordinator.observePending().value.none { it.id == approval.id })
        }

    @Test
    @DisplayName("approve logs approved")
    fun approveLogs() =
        runTest {
            val serverLog = RecordingServerLogRepository()
            val coordinator = newCoordinator(serverLog)
            val approval = coordinator.createPending(req(), 0L)
            coordinator.approve(approval.id, 1L)

            val entry = serverLog.ofType(ServerLogEntry.Type.OAUTH).single()
            assertTrue(entry.message.contains("approved for Claude"))
        }

    @Test
    @DisplayName("late approve logs expired")
    fun lateApproveLogsExpired() =
        runTest {
            val serverLog = RecordingServerLogRepository()
            val coordinator = newCoordinator(serverLog)
            val approval = coordinator.createPending(req(), 0L)
            coordinator.approve(approval.id, OAuthPolicy.APPROVAL_WINDOW_MS + 1)

            val entry = serverLog.ofType(ServerLogEntry.Type.OAUTH).single()
            assertTrue(entry.message.contains("expired"))
        }

    @Test
    @DisplayName("deny logs denied")
    fun denyLogs() =
        runTest {
            val serverLog = RecordingServerLogRepository()
            val coordinator = newCoordinator(serverLog)
            val approval = coordinator.createPending(req(), 0L)
            coordinator.deny(approval.id)

            val entry = serverLog.ofType(ServerLogEntry.Type.OAUTH).single()
            assertTrue(entry.message.contains("denied for Claude"))
        }

    @Test
    @DisplayName("stateOf lazy expiry logs expired")
    fun stateOfLazyExpiryLogs() =
        runTest {
            val serverLog = RecordingServerLogRepository()
            val coordinator = newCoordinator(serverLog)
            val approval = coordinator.createPending(req(), 0L)
            coordinator.stateOf(approval.id, OAuthPolicy.APPROVAL_WINDOW_MS + 1)

            val entry = serverLog.ofType(ServerLogEntry.Type.OAUTH).single()
            assertTrue(entry.message.contains("expired"))
        }

    @Test
    @DisplayName("cap eviction logs nothing")
    fun capEvictionLogsNothing() =
        runTest {
            val serverLog = RecordingServerLogRepository()
            val coordinator = newCoordinator(serverLog)
            repeat(OAuthPolicy.MAX_PENDING_APPROVALS + 1) { coordinator.createPending(req(), 0L) }

            assertTrue(serverLog.ofType(ServerLogEntry.Type.OAUTH).isEmpty())
        }

    @Test
    @DisplayName("purge of stale pending logs expired")
    fun purgeStalePendingLogsExpired() =
        runTest {
            val serverLog = RecordingServerLogRepository()
            val coordinator = newCoordinator(serverLog)
            coordinator.createPending(req(), 0L)
            coordinator.createPending(req(), OAuthPolicy.APPROVAL_WINDOW_MS + 1)

            val entries = serverLog.ofType(ServerLogEntry.Type.OAUTH)
            assertEquals(1, entries.size)
            assertTrue(entries.first().message.contains("expired"))
        }
}
