package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.repository.ShellSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("ShellAdbConfigApplier")
class ShellAdbConfigApplierTest {
    private val settings = mockk<ShellSettings>(relaxed = true)
    private val applier = ShellAdbConfigApplier(settings)

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        coEvery { settings.updateShellLimit(any(), any()) } answers { Result.success(secondArg()) }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun intent(
        booleans: Map<String, Boolean> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        ints: Map<String, Int> = emptyMap(),
    ): Intent =
        mockk {
            every { hasExtra(any()) } answers { firstArg<String>() in booleans.keys + strings.keys + ints.keys }
            every { getBooleanExtra(any(), any()) } answers { booleans[firstArg()] ?: secondArg() }
            every { getStringExtra(any()) } answers { strings[firstArg()] }
            every { getIntExtra(any(), any()) } answers { ints[firstArg()] ?: secondArg() }
        }

    @Test
    fun `booleans applied only when present`() =
        runTest {
            applier.apply(intent())

            coVerify(exactly = 0) { settings.updateShellElevationEnabled(any()) }
            coVerify(exactly = 0) { settings.updateShellAutoAllowElevation(any()) }

            applier.apply(
                intent(
                    booleans =
                        mapOf(
                            ShellAdbConfigApplier.EXTRA_ELEVATION_ENABLED to true,
                            ShellAdbConfigApplier.EXTRA_AUTO_ALLOW_ELEVATION to false,
                        ),
                ),
            )

            coVerify(exactly = 1) { settings.updateShellElevationEnabled(true) }
            coVerify(exactly = 1) { settings.updateShellAutoAllowElevation(false) }
        }

    @Test
    fun `pattern lists applied`() =
        runTest {
            applier.apply(
                intent(
                    strings =
                        mapOf(
                            ShellAdbConfigApplier.EXTRA_BLOCKLIST to """["*reboot*"]""",
                            ShellAdbConfigApplier.EXTRA_ALLOWLIST to """["ls -*"]""",
                        ),
                ),
            )

            coVerify(exactly = 1) { settings.updateShellBlocklist(listOf("*reboot*")) }
            coVerify(exactly = 1) { settings.updateShellAllowlist(listOf("ls -*")) }
        }

    @Test
    fun `invalid pattern JSON is ignored`() =
        runTest {
            applier.apply(intent(strings = mapOf(ShellAdbConfigApplier.EXTRA_BLOCKLIST to "not json")))
            applier.apply(intent(strings = mapOf(ShellAdbConfigApplier.EXTRA_ALLOWLIST to """{"a":1}""")))

            coVerify(exactly = 0) { settings.updateShellBlocklist(any()) }
            coVerify(exactly = 0) { settings.updateShellAllowlist(any()) }
        }

    @ParameterizedTest
    @EnumSource(ShellLimit::class)
    fun `every limit key is applied`(limit: ShellLimit) =
        runTest {
            applier.apply(intent(ints = mapOf(limit.key to limit.min)))

            coVerify(exactly = 1) { settings.updateShellLimit(limit, limit.min) }
        }

    @Test
    fun `invalid limit is ignored`() =
        runTest {
            coEvery { settings.updateShellLimit(any(), any()) } returns
                Result.failure(IllegalArgumentException("Must be between 1 and 64"))

            applier.apply(intent(ints = mapOf(ShellLimit.MAX_RUNNING_PROCESSES.key to 0)))

            coVerify(exactly = 2) { settings.updateShellLimit(ShellLimit.MAX_RUNNING_PROCESSES, 0) }
            verify { Log.w(any<String>(), match<String> { it.contains("shell_max_running_processes") }) }
        }

    @Test
    fun `a limit refused first is retried after the others`() =
        runTest {
            val defaultLimit = ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES
            val maxLimit = ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES
            coEvery { settings.updateShellLimit(defaultLimit, 200) } returnsMany
                listOf(Result.failure(IllegalArgumentException("above the max")), Result.success(200))

            applier.apply(intent(ints = mapOf(defaultLimit.key to 200, maxLimit.key to 300)))

            coVerifyOrder {
                settings.updateShellLimit(defaultLimit, 200)
                settings.updateShellLimit(maxLimit, 300)
                settings.updateShellLimit(defaultLimit, 200)
            }
        }
}
