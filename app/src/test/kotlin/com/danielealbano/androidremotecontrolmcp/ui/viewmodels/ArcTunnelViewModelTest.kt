package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlert
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlerts
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelServiceProblem
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelServiceStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArcTunnelViewModel")
class ArcTunnelViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private val config = MutableStateFlow(ServerConfig())
    private val enrollmentState = MutableStateFlow<ArcTunnelEnrollmentState>(ArcTunnelEnrollmentState.Idle)
    private val alert = MutableStateFlow<ArcTunnelCtAlert?>(null)
    private val repository = mockk<SettingsRepository>(relaxed = true)
    private val enrollment = mockk<ArcTunnelEnrollment>(relaxed = true)
    private val ctAlerts = mockk<ArcTunnelCtAlerts>(relaxed = true)
    private val serviceStatus = mockk<ArcTunnelServiceStatus>()
    private val renewal = MutableStateFlow<ArcTunnelRenewalState>(ArcTunnelRenewalState.Idle)
    private val renewals = mockk<ArcTunnelRenewals>(relaxed = true) { every { state } returns renewal }
    private var savedUrl: String? = null
    private lateinit var viewModel: ArcTunnelViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.serverConfig } returns config
        every { enrollment.state } returns enrollmentState
        every { enrollment.savedUrl(any()) } answers { savedUrl }
        every { enrollment.savedCtAlert(any()) } returns null
        every { ctAlerts.alert } returns alert
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ArcTunnelViewModel {
        viewModel = ArcTunnelViewModel(repository, enrollment, renewals, ctAlerts, serviceStatus, dispatcher)
        return viewModel
    }

    private fun arc(tunnelEnabled: Boolean): ServerConfig =
        ServerConfig(tunnelEnabled = tunnelEnabled, tunnelProvider = TunnelProviderType.ARC_TUNNEL)

    private fun reportedConfig() = arc(tunnelEnabled = true)

    /** Starts the view model on [settings] and lets it read them; polling only begins once something collects. */
    private fun TestScope.startForStatus(settings: ServerConfig) {
        config.value = settings
        savedUrl = "https://name.d.arc-tunnel.io"
        createViewModel()
        advanceUntilIdle()
    }

    private fun TestScope.collectProblem(): Job {
        val collecting = UnconfinedTestDispatcher(testScheduler)
        return launch(collecting) { viewModel.serviceProblem.collect {} }
    }

    @Test
    fun `the status page is not asked while nothing collects the problem`() =
        runTest(dispatcher) {
            startForStatus(reportedConfig())

            advanceTimeBy(STATUS_POLL_MS * 2)

            coVerify(exactly = 0) { serviceStatus.currentProblem() }
        }

    @Test
    fun `a reported problem shows while collected, and refreshes it`() =
        runTest(dispatcher) {
            coEvery { serviceStatus.currentProblem() } returns PROBLEM
            startForStatus(reportedConfig())

            val collector = collectProblem()
            runCurrent()
            assertEquals(PROBLEM, viewModel.serviceProblem.value)
            coVerify(exactly = 1) { serviceStatus.currentProblem() }

            advanceTimeBy(STATUS_POLL_MS + 1)
            coVerify(exactly = 2) { serviceStatus.currentProblem() }

            collector.cancel()
        }

    @Test
    fun `the alert goes away once the page no longer reports a problem`() =
        runTest(dispatcher) {
            coEvery { serviceStatus.currentProblem() } returnsMany listOf(PROBLEM, null)
            startForStatus(reportedConfig())

            val collector = collectProblem()
            runCurrent()
            assertEquals(PROBLEM, viewModel.serviceProblem.value)

            advanceTimeBy(STATUS_POLL_MS + 1)
            assertEquals(null, viewModel.serviceProblem.value)

            collector.cancel()
        }

    @Test
    fun `leaving the screen stops asking and drops the alert`() =
        runTest(dispatcher) {
            coEvery { serviceStatus.currentProblem() } returns PROBLEM
            startForStatus(reportedConfig())
            val collector = collectProblem()
            runCurrent()

            collector.cancel()
            advanceTimeBy(STATUS_POLL_MS * 3)

            coVerify(exactly = 1) { serviceStatus.currentProblem() }
            assertEquals(null, viewModel.serviceProblem.value)
        }

    @Test
    fun `no saved identity means the page is not asked`() =
        runTest(dispatcher) {
            coEvery { serviceStatus.currentProblem() } returns PROBLEM
            config.value = reportedConfig()
            savedUrl = null
            createViewModel()
            advanceUntilIdle()

            val collector = collectProblem()
            advanceTimeBy(STATUS_POLL_MS)

            coVerify(exactly = 0) { serviceStatus.currentProblem() }
            assertEquals(null, viewModel.serviceProblem.value)
            collector.cancel()
        }

    @Test
    fun `another provider, the tunnel off or a self-hosted server means the page is not asked`() =
        runTest(dispatcher) {
            coEvery { serviceStatus.currentProblem() } returns PROBLEM
            val notRelevant =
                listOf(
                    reportedConfig().copy(tunnelProvider = TunnelProviderType.NGROK),
                    reportedConfig().copy(tunnelProvider = TunnelProviderType.CLOUDFLARE),
                    reportedConfig().copy(tunnelEnabled = false),
                    reportedConfig().copy(arcTunnelBaseAddress = "tunnel.example.org"),
                )
            for (settings in notRelevant) {
                startForStatus(settings)

                val collector = collectProblem()
                advanceTimeBy(STATUS_POLL_MS)

                coVerify(exactly = 0) { serviceStatus.currentProblem() }
                assertEquals(null, viewModel.serviceProblem.value, "$settings")
                collector.cancel()
            }
        }

    @Test
    fun `moving to another provider drops the alert and stops asking`() =
        runTest(dispatcher) {
            coEvery { serviceStatus.currentProblem() } returns PROBLEM
            startForStatus(reportedConfig())
            val collector = collectProblem()
            runCurrent()
            assertEquals(PROBLEM, viewModel.serviceProblem.value)

            config.value = reportedConfig().copy(tunnelProvider = TunnelProviderType.NGROK)
            runCurrent()
            assertEquals(null, viewModel.serviceProblem.value)

            advanceTimeBy(STATUS_POLL_MS * 3)
            coVerify(exactly = 1) { serviceStatus.currentProblem() }
            collector.cancel()
        }

    @Test
    fun `url is Loading until the first config then NotEnrolled or Enrolled`() =
        runTest(dispatcher) {
            createViewModel()

            assertEquals(ArcTunnelUrlState.Loading, viewModel.url.value)
            advanceUntilIdle()
            assertEquals(ArcTunnelUrlState.NotEnrolled, viewModel.url.value)

            savedUrl = URL
            config.value = config.value.copy(tunnelEnabled = true)
            advanceUntilIdle()
            assertEquals(ArcTunnelUrlState.Enrolled(URL), viewModel.url.value)
        }

    @Test
    fun `config changes refresh base address input and url`() =
        runTest(dispatcher) {
            every { enrollment.savedUrl(ArcTunnelEnv(OTHER_BASE)) } returns OTHER_URL
            createViewModel()
            advanceUntilIdle()

            config.value = config.value.copy(arcTunnelBaseAddress = OTHER_BASE)
            advanceUntilIdle()

            assertEquals(OTHER_BASE, viewModel.baseAddressInput.value)
            assertEquals(ArcTunnelUrlState.Enrolled(OTHER_URL), viewModel.url.value)
        }

    @Test
    fun `updateBaseAddressInput validates without persisting`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()

            viewModel.updateBaseAddressInput("x")
            advanceUntilIdle()

            assertEquals("x", viewModel.baseAddressInput.value)
            assertFalse(viewModel.baseAddressValid.value)
            coVerify(exactly = 0) { repository.updateArcTunnelBaseAddress(any()) }
        }

    @Test
    fun `commitBaseAddress persists a valid changed value only`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()

            viewModel.commitBaseAddress()
            viewModel.updateBaseAddressInput("x")
            viewModel.commitBaseAddress()
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.updateArcTunnelBaseAddress(any()) }

            viewModel.updateBaseAddressInput(OTHER_BASE)
            viewModel.commitBaseAddress()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.updateArcTunnelBaseAddress(OTHER_BASE) }
        }

    @Test
    fun `commitBaseAddress does nothing before the first configuration`() =
        runTest(dispatcher) {
            createViewModel()

            viewModel.updateBaseAddressInput(OTHER_BASE)
            viewModel.commitBaseAddress()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.updateArcTunnelBaseAddress(any()) }
        }

    @Test
    fun `updateCtCheckEnabled persists`() =
        runTest(dispatcher) {
            createViewModel()

            viewModel.updateCtCheckEnabled(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.updateArcTunnelCtCheckEnabled(false) }
        }

    @Test
    fun `enableFreeTunnel selects the provider then enables then requests`() =
        runTest(dispatcher) {
            var requestedDuringWrites: Boolean? = null
            coEvery { repository.updateTunnelEnabled(true) } coAnswers {
                requestedDuringWrites = viewModel.enrollmentRequested.value
            }
            createViewModel()
            advanceUntilIdle()

            viewModel.enableFreeTunnel()
            advanceUntilIdle()

            coVerifyOrder {
                repository.updateTunnelProvider(TunnelProviderType.ARC_TUNNEL)
                repository.updateTunnelEnabled(true)
            }
            assertEquals(false, requestedDuringWrites)
            assertTrue(viewModel.enrollmentRequested.value)
        }

    @Test
    fun `enableFreeTunnel keeps the request across the provider-only emission`() =
        runTest(dispatcher) {
            config.value = ServerConfig(tunnelEnabled = false, tunnelProvider = TunnelProviderType.CLOUDFLARE)
            createViewModel()
            advanceUntilIdle()

            viewModel.enableFreeTunnel()
            advanceUntilIdle()
            config.value = arc(tunnelEnabled = false)
            advanceUntilIdle()
            config.value = arc(tunnelEnabled = true)
            advanceUntilIdle()

            assertTrue(viewModel.enrollmentRequested.value)
        }

    @Test
    fun `requestEnrollment sets the flag and an enrolled url clears it`() =
        runTest(dispatcher) {
            config.value = arc(tunnelEnabled = true)
            createViewModel()
            advanceUntilIdle()

            viewModel.requestEnrollment()
            assertTrue(viewModel.enrollmentRequested.value)

            savedUrl = URL
            config.value = config.value.copy(arcTunnelCtCheckEnabled = false)
            advanceUntilIdle()
            assertFalse(viewModel.enrollmentRequested.value)
        }

    @Test
    fun `request is cleared when remote access turns off or another provider is selected`() =
        runTest(dispatcher) {
            config.value = arc(tunnelEnabled = true)
            createViewModel()
            advanceUntilIdle()

            viewModel.requestEnrollment()
            config.value = arc(tunnelEnabled = false)
            advanceUntilIdle()
            assertFalse(viewModel.enrollmentRequested.value)

            config.value = arc(tunnelEnabled = true)
            advanceUntilIdle()
            viewModel.requestEnrollment()
            config.value = config.value.copy(tunnelProvider = TunnelProviderType.CLOUDFLARE)
            advanceUntilIdle()
            assertFalse(viewModel.enrollmentRequested.value)
        }

    @Test
    fun `enroll clears the request and requests enrollment with the persisted env`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.requestEnrollment()

            viewModel.enroll()
            advanceUntilIdle()

            assertFalse(viewModel.enrollmentRequested.value)
            verify(exactly = 1) { enrollment.requestEnroll(ArcTunnelEnv(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)) }
            coVerify(exactly = 0) { repository.updateArcTunnelBaseAddress(any()) }
        }

    @Test
    fun `enroll uses the typed base address and persists it first`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.updateBaseAddressInput(OTHER_BASE)

            viewModel.enroll()
            advanceUntilIdle()

            coVerifyOrder {
                repository.updateArcTunnelBaseAddress(OTHER_BASE)
                enrollment.requestEnroll(ArcTunnelEnv(OTHER_BASE))
            }
        }

    @Test
    fun `enroll ignores an invalid typed base address`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.updateBaseAddressInput("x")

            viewModel.enroll()
            advanceUntilIdle()

            verify(exactly = 1) { enrollment.requestEnroll(ArcTunnelEnv(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)) }
            coVerify(exactly = 0) { repository.updateArcTunnelBaseAddress(any()) }
        }

    @Test
    fun `a config emission does not overwrite the typed input`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.updateBaseAddressInput("a.b")

            config.value = config.value.copy(arcTunnelCtCheckEnabled = false)
            advanceUntilIdle()

            assertEquals("a.b", viewModel.baseAddressInput.value)
        }

    @Test
    fun `url refreshes when the enrollment state leaves Enrolling`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()
            enrollmentState.value = ArcTunnelEnrollmentState.Enrolling
            advanceUntilIdle()
            assertEquals(ArcTunnelUrlState.NotEnrolled, viewModel.url.value)

            savedUrl = URL
            enrollmentState.value = ArcTunnelEnrollmentState.Idle
            advanceUntilIdle()

            assertEquals(ArcTunnelUrlState.Enrolled(URL), viewModel.url.value)
        }

    @Test
    fun `url refreshes when a setup failure is cleared`() =
        runTest(dispatcher) {
            createViewModel()
            enrollmentState.value = ArcTunnelEnrollmentState.Failed("acme_failed")
            advanceUntilIdle()
            assertEquals(ArcTunnelUrlState.NotEnrolled, viewModel.url.value)

            savedUrl = URL
            enrollmentState.value = ArcTunnelEnrollmentState.Idle
            advanceUntilIdle()

            assertEquals(ArcTunnelUrlState.Enrolled(URL), viewModel.url.value)
        }

    @Test
    fun `url refreshes when a tunnel-start renewal ends`() =
        runTest(dispatcher) {
            createViewModel()
            renewal.value = ArcTunnelRenewalState.Renewing
            advanceUntilIdle()
            assertEquals(ArcTunnelUrlState.NotEnrolled, viewModel.url.value)

            savedUrl = URL
            renewal.value = ArcTunnelRenewalState.Idle
            advanceUntilIdle()

            assertEquals(ArcTunnelUrlState.Enrolled(URL), viewModel.url.value)
        }

    @Test
    fun `url refreshes when a renewal ends after installing the certificate`() =
        runTest(dispatcher) {
            createViewModel()
            renewal.value = ArcTunnelRenewalState.Renewing
            advanceUntilIdle()
            savedUrl = URL
            renewal.value = ArcTunnelRenewalState.Installing
            advanceUntilIdle()
            assertEquals(ArcTunnelUrlState.NotEnrolled, viewModel.url.value)

            renewal.value = ArcTunnelRenewalState.Idle
            advanceUntilIdle()

            assertEquals(ArcTunnelUrlState.Enrolled(URL), viewModel.url.value)
        }

    @Test
    fun `cancelEnrollment clears the request and disables remote access`() =
        runTest(dispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.requestEnrollment()

            viewModel.cancelEnrollment()
            advanceUntilIdle()

            assertFalse(viewModel.enrollmentRequested.value)
            coVerify(exactly = 1) { repository.updateTunnelEnabled(false) }
        }

    @Test
    fun `rotateName clears and dismisses the alert then requests a rotation`() =
        runTest(dispatcher) {
            val env = ArcTunnelEnv(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)
            createViewModel()
            advanceUntilIdle()

            viewModel.rotateName()
            advanceUntilIdle()

            coVerifyOrder {
                enrollment.dismissCtAlert(env)
                ctAlerts.dismiss()
                enrollment.requestRotate(env)
            }
        }

    @Test
    fun `dismissCtAlert clears the persisted alert before hiding the banner`() =
        runTest(dispatcher) {
            val env = ArcTunnelEnv(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)
            createViewModel()
            advanceUntilIdle()

            viewModel.dismissCtAlert()
            advanceUntilIdle()

            coVerifyOrder {
                enrollment.dismissCtAlert(env)
                ctAlerts.dismiss()
            }
        }

    @Test
    fun `a persisted alert is restored once at init when none is shown`() =
        runTest(dispatcher) {
            val persisted = ArcTunnelCtAlert("abcdefghij.d.arc-tunnel.io", "f00d")
            every { enrollment.savedCtAlert(any()) } returns persisted
            createViewModel()
            advanceUntilIdle()

            config.value = config.value.copy(arcTunnelCtCheckEnabled = false)
            advanceUntilIdle()

            verify(exactly = 1) { ctAlerts.restore(persisted) }
        }

    private companion object {
        const val STATUS_POLL_MS = 60_000L
        val PROBLEM = ArcTunnelServiceProblem("degraded", listOf("Tunnels"))

        const val URL = "https://abcdefghij.d.arc-tunnel.io"
        const val OTHER_BASE = "tunnel.example.org"
        const val OTHER_URL = "https://abcdefghij.d.tunnel.example.org"
    }
}
