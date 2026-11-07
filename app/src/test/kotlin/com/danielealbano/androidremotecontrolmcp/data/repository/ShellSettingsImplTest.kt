package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ShellPatternKind
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuProviderSwitch
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShellSettingsImpl")
class ShellSettingsImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val log = RecordingServerLogRepository()
    private val switch = mockk<ShizukuProviderSwitch>(relaxed = true)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: ShellSettingsImpl

    private var fileCounter = 0

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        fileCounter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "shell_settings_$fileCounter.preferences_pb") },
            )
        val changeLogger = SettingsChangeLogger(log, testDispatcher, SettingsChangeLogger.COALESCE_WINDOW_MS)
        settings = ShellSettingsImpl(dataStore, changeLogger, switch)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private suspend fun config(): ShellConfig = mapShellConfig(dataStore.data.first())

    @Test
    fun `defaults when nothing is stored`() {
        val config = mapShellConfig(emptyPreferences())

        assertFalse(config.elevationEnabled)
        assertFalse(config.autoAllowElevation)
        assertTrue(config.blocklist.isEmpty())
        assertTrue(config.allowlist.isEmpty())
        ShellLimit.entries.forEach { assertEquals(it.defaultValue, config.limit(it), it.key) }
        assertEquals(30, config.backgroundTimeoutDefaultMinutes)
    }

    @Test
    fun `syncShizukuProvider applies the stored elevation switch`() =
        testScope.runTest {
            settings.syncShizukuProvider()
            verify(exactly = 1) { switch.setEnabled(false) }

            dataStore.edit { it[SHELL_ELEVATION_ENABLED_KEY] = true }
            settings.syncShizukuProvider()
            verify(exactly = 1) { switch.setEnabled(true) }

            dataStore.edit { it[SHELL_ELEVATION_ENABLED_KEY] = false }
            settings.syncShizukuProvider()
            verify(exactly = 2) { switch.setEnabled(false) }
        }

    @Test
    fun `elevation toggle persists and switches the provider`() =
        testScope.runTest {
            settings.updateShellElevationEnabled(true)

            assertTrue(config().elevationEnabled)
            verify(exactly = 1) { switch.setEnabled(true) }

            settings.updateShellElevationEnabled(false)

            assertFalse(config().elevationEnabled)
            verify(exactly = 1) { switch.setEnabled(false) }
        }

    @Test
    fun `auto-allow toggle persists`() =
        testScope.runTest {
            settings.updateShellAutoAllowElevation(true)

            assertTrue(config().autoAllowElevation)

            settings.updateShellAutoAllowElevation(false)

            assertFalse(config().autoAllowElevation)
        }

    @Test
    fun `patterns are trimmed, blanks dropped, duplicates removed`() =
        testScope.runTest {
            val input = listOf(" ls -* ", "", "ls -*", "*reboot*")

            settings.updateShellBlocklist(input)
            settings.updateShellAllowlist(input)

            assertEquals(listOf("ls -*", "*reboot*"), config().blocklist)
            assertEquals(listOf("ls -*", "*reboot*"), config().allowlist)
        }

    @Test
    fun `editShellPatterns changes the stored list in one edit`() =
        testScope.runTest {
            settings.updateShellBlocklist(listOf("a"))

            settings.editShellPatterns(ShellPatternKind.BLOCKLIST) { it + "b" }

            assertEquals(listOf("a", "b"), config().blocklist)

            val first = launch { settings.editShellPatterns(ShellPatternKind.BLOCKLIST) { it + "c" } }
            val second = launch { settings.editShellPatterns(ShellPatternKind.BLOCKLIST) { it + "d" } }
            joinAll(first, second)

            assertTrue(config().blocklist.containsAll(listOf("a", "b", "c", "d")))
        }

    @Test
    fun `invalid stored pattern JSON reads as empty`() =
        testScope.runTest {
            dataStore.edit { it[SHELL_BLOCKLIST_KEY] = "not json" }

            assertTrue(config().blocklist.isEmpty())
        }

    @ParameterizedTest
    @EnumSource(ShellLimit::class)
    fun `every limit accepts min and max and refuses outside`(limit: ShellLimit) =
        testScope.runTest {
            when (limit) {
                ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES ->
                    settings.updateShellLimit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES, 1440).getOrThrow()
                ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES ->
                    settings.updateShellLimit(ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES, 1).getOrThrow()
                ShellLimit.MAX_RUNNING_PROCESSES, ShellLimit.MAX_FINISHED_PROCESSES, ShellLimit.OUTPUT_BUFFER_KIB ->
                    MINIMUMS.filterKeys { it != limit }.forEach { (other, value) ->
                        settings.updateShellLimit(other, value).getOrThrow()
                    }
                else -> Unit
            }

            assertTrue(settings.updateShellLimit(limit, limit.min).isSuccess)
            assertTrue(settings.updateShellLimit(limit, limit.max).isSuccess)
            assertEquals(limit.max, config().limit(limit))

            val below = settings.updateShellLimit(limit, limit.min - 1)
            val above = settings.updateShellLimit(limit, limit.max + 1)

            assertEquals("Must be between ${limit.min} and ${limit.max}", below.exceptionOrNull()?.message)
            assertEquals("Must be between ${limit.min} and ${limit.max}", above.exceptionOrNull()?.message)
            assertEquals(limit.max, config().limit(limit))
        }

    @Test
    fun `output memory over 64 MiB is refused`() =
        testScope.runTest {
            assertTrue(settings.updateShellLimit(ShellLimit.OUTPUT_BUFFER_KIB, 1365).isSuccess)

            val tooLarge = settings.updateShellLimit(ShellLimit.OUTPUT_BUFFER_KIB, 1366)

            assertEquals(MEMORY_CAP_MESSAGE, tooLarge.exceptionOrNull()?.message)
            assertEquals(1365, config().limit(ShellLimit.OUTPUT_BUFFER_KIB))

            settings.updateShellLimit(ShellLimit.OUTPUT_BUFFER_KIB, 1024).getOrThrow()

            assertTrue(settings.updateShellLimit(ShellLimit.MAX_RUNNING_PROCESSES, 32).isSuccess)
            assertEquals(
                MEMORY_CAP_MESSAGE,
                settings.updateShellLimit(ShellLimit.MAX_RUNNING_PROCESSES, 33).exceptionOrNull()?.message,
            )
            assertEquals(
                MEMORY_CAP_MESSAGE,
                settings.updateShellLimit(ShellLimit.MAX_FINISHED_PROCESSES, 33).exceptionOrNull()?.message,
            )
            assertEquals(32, config().limit(ShellLimit.MAX_RUNNING_PROCESSES))
            assertEquals(32, config().limit(ShellLimit.MAX_FINISHED_PROCESSES))
        }

    @Test
    fun `outputMemoryKib uses the new value`() {
        assertEquals(98_304L, ShellConfig().outputMemoryKib(ShellLimit.OUTPUT_BUFFER_KIB, 2048))
        assertEquals(16_384L, ShellConfig().outputMemoryKib(ShellLimit.MAX_FINISHED_PROCESSES, 0))
    }

    @Test
    fun `background default above the max is refused`() =
        testScope.runTest {
            settings.updateShellLimit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES, 60).getOrThrow()

            val result = settings.updateShellLimit(ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES, 61)

            assertEquals("Must not exceed the maximum background timeout (60)", result.exceptionOrNull()?.message)
            assertEquals(30, config().limit(ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES))
        }

    @Test
    fun `max below the default is refused`() =
        testScope.runTest {
            val result = settings.updateShellLimit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES, 20)

            assertEquals("Must not be below the default background timeout (30)", result.exceptionOrNull()?.message)
            assertEquals(120, config().limit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES))
        }

    @Test
    fun `out-of-range stored limit falls back to the default`() =
        testScope.runTest {
            dataStore.edit { it[intPreferencesKey("shell_max_running_processes")] = 0 }

            assertEquals(16, config().limit(ShellLimit.MAX_RUNNING_PROCESSES))
        }

    @Test
    fun `changes are logged`() =
        testScope.runTest {
            settings.updateShellElevationEnabled(true)
            settings.updateShellAutoAllowElevation(true)
            settings.updateShellLimit(ShellLimit.OUTPUT_PER_CALL_KIB, 8).getOrThrow()
            settings.updateShellBlocklist(listOf("*reboot*"))
            advanceUntilIdle()

            val messages = log.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }

            assertEquals(
                setOf(
                    "Elevated shell commands allowed",
                    "Auto-allow of elevated shell commands enabled",
                    "Shell setting shell_output_per_call_kib changed from 64 to 8",
                    "Shell blocklist updated (1 patterns)",
                ),
                messages.toSet(),
            )
        }

    private companion object {
        val MINIMUMS =
            mapOf(
                ShellLimit.MAX_RUNNING_PROCESSES to 1,
                ShellLimit.MAX_FINISHED_PROCESSES to 0,
                ShellLimit.OUTPUT_BUFFER_KIB to 64,
            )

        const val MEMORY_CAP_MESSAGE =
            "Running plus kept processes times the output kept per process must not exceed 64 MiB"
    }
}
