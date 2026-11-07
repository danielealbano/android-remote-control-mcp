package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ShellPatternKind
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.shell.AppRestarter
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuApi
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuState
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuStatusProvider
import com.danielealbano.androidremotecontrolmcp.services.shell.SuProbeResult
import com.danielealbano.androidremotecontrolmcp.services.shell.SuShell
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import rikka.shizuku.Shizuku

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShellSettingsViewModel")
class ShellSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var stored = ServerConfig(shellConfig = ShellConfig(limits = mapOf(ShellLimit.MAX_RUNNING_PROCESSES to 8)))
    private val settingsRepository =
        mockk<SettingsRepository>(relaxUnitFun = true) {
            every { serverConfig } returns MutableStateFlow(stored)
            coEvery { getServerConfig() } answers { stored }
            coEvery { updateShellLimit(any(), any()) } answers { Result.success(secondArg()) }
        }
    private val shizukuStatus = mockk<ShizukuStatusProvider> { every { state() } returns ShizukuState.NotRunning }
    private val listener = slot<Shizuku.OnRequestPermissionResultListener>()
    private val shizukuApi =
        mockk<ShizukuApi>(relaxed = true) { every { addPermissionResultListener(capture(listener)) } returns Unit }
    private val su = mockk<SuShell> { every { locate() } returns null }
    private val appRestarter = mockk<AppRestarter>(relaxed = true)

    private lateinit var viewModel: ShellSettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = ShellSettingsViewModel(settingsRepository, shizukuStatus, shizukuApi, su, appRestarter, dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `limit fields start from the stored config`() {
        ShellLimit.entries.forEach { limit ->
            assertEquals(stored.shellConfig.limit(limit).toString(), viewModel.limitFields.value[limit]?.text)
        }
    }

    @Test
    fun `limit edits are saved in the order typed`() {
        val limit = ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES

        viewModel.updateLimit(limit, "1")
        viewModel.updateLimit(limit, "12")
        viewModel.updateLimit(limit, "120")

        coVerifyOrder {
            settingsRepository.updateShellLimit(limit, 1)
            settingsRepository.updateShellLimit(limit, 12)
            settingsRepository.updateShellLimit(limit, 120)
        }
    }

    @Test
    fun `invalid limit input is not saved`() {
        viewModel.updateLimit(ShellLimit.MAX_RUNNING_PROCESSES, "abc")

        coVerify(exactly = 0) { settingsRepository.updateShellLimit(any(), any()) }
    }

    @Test
    fun `refresh reloads the limit fields`() {
        stored = ServerConfig(shellConfig = ShellConfig(limits = mapOf(ShellLimit.MAX_RUNNING_PROCESSES to 3)))

        viewModel.refresh()

        assertEquals("3", viewModel.limitFields.value[ShellLimit.MAX_RUNNING_PROCESSES]?.text)
    }

    @Test
    fun `switches`() {
        viewModel.setSwitch(ShellSwitch.ELEVATION, true)
        viewModel.setSwitch(ShellSwitch.AUTO_ALLOW, true)

        coVerify(exactly = 1) { settingsRepository.updateShellElevationEnabled(true) }
        coVerify(exactly = 1) { settingsRepository.updateShellAutoAllowElevation(true) }
    }

    @Test
    fun `patterns are added trimmed and removed`() {
        val changes = mutableListOf<(List<String>) -> List<String>>()
        coEvery { settingsRepository.editShellPatterns(ShellPatternKind.ALLOWLIST, capture(changes)) } returns Unit

        viewModel.addPattern(ShellPatternKind.ALLOWLIST, " ls -* ")
        viewModel.addPattern(ShellPatternKind.ALLOWLIST, "  ")
        viewModel.removePattern(ShellPatternKind.ALLOWLIST, "ls -*")

        assertEquals(2, changes.size)
        assertEquals(listOf("x", "ls -*"), changes[0](listOf("x")))
        assertEquals(listOf("x"), changes[1](listOf("x", "ls -*")))
    }

    @Test
    fun `refresh updates the status`() {
        every { shizukuStatus.state() } returns ShizukuState.Ready(2000)
        every { su.locate() } returns "/system/bin/su"
        coEvery { su.probe() } returns SuProbeResult.Works

        viewModel.refresh()

        assertEquals(ShizukuState.Ready(2000), viewModel.shizukuState.value)
        assertEquals(SuStatus.Found("/system/bin/su"), viewModel.suStatus.value)

        viewModel.testSu()
        viewModel.refresh()

        assertEquals(SuStatus.Works("/system/bin/su"), viewModel.suStatus.value)

        every { su.locate() } returns null
        viewModel.refresh()

        assertEquals(SuStatus.NotFound, viewModel.suStatus.value)
    }

    @Test
    fun `su test`() {
        val probe = CompletableDeferred<SuProbeResult>()
        every { su.locate() } returns "/system/bin/su"
        coEvery { su.probe() } coAnswers { probe.await() }

        viewModel.testSu()

        assertEquals(SuStatus.Testing("/system/bin/su"), viewModel.suStatus.value)

        probe.complete(SuProbeResult.Works)

        assertEquals(SuStatus.Works("/system/bin/su"), viewModel.suStatus.value)

        coEvery { su.probe() } returns SuProbeResult.Fails("denied")
        viewModel.testSu()

        assertEquals(SuStatus.Fails("/system/bin/su", "denied"), viewModel.suStatus.value)
    }

    @Test
    fun `Shizuku permission result refreshes`() {
        every { shizukuStatus.state() } returns ShizukuState.Ready(2000)

        listener.captured.onRequestPermissionResult(0, 0)

        verify(atLeast = 2) { shizukuStatus.state() }
        assertEquals(ShizukuState.Ready(2000), viewModel.shizukuState.value)
    }

    @Test
    fun `restart app asks the restarter`() {
        viewModel.restartApp()

        verify(exactly = 1) { appRestarter.restart() }
    }

    @Test
    fun `listener removed on clear`() {
        ShellSettingsViewModel::class.java
            .getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(viewModel)

        verify(exactly = 1) { shizukuApi.removePermissionResultListener(listener.captured) }
    }
}
