package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsRepositoryImpl update-check settings")
class SettingsRepositoryUpdateCheckTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepositoryImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "update_check.preferences_pb") },
            )
        val changeLogger =
            SettingsChangeLogger(
                RecordingServerLogRepository(),
                testDispatcher,
                SettingsChangeLogger.COALESCE_WINDOW_MS,
            )
        repository =
            SettingsRepositoryImpl(
                dataStore,
                changeLogger,
                EventChannelSettingsImpl(dataStore, changeLogger),
                ArcTunnelSettingsImpl(dataStore, changeLogger),
                DownloadSettingsImpl(dataStore, changeLogger),
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `autoUpdateCheckEnabled defaults to true and round-trips`() =
        testScope.runTest {
            assertTrue(repository.autoUpdateCheckEnabled.first())

            repository.updateAutoUpdateCheckEnabled(false)
            assertFalse(repository.autoUpdateCheckEnabled.first())

            repository.updateAutoUpdateCheckEnabled(true)
            assertTrue(repository.autoUpdateCheckEnabled.first())
        }

    @Test
    fun `availableUpdate defaults to null`() =
        testScope.runTest {
            assertNull(repository.availableUpdate.first())
        }

    @Test
    fun `setAvailableUpdate persists and clears`() =
        testScope.runTest {
            val update = AvailableUpdate("1.11.0", "https://example/rel")
            repository.setAvailableUpdate(update)
            assertEquals(update, repository.availableUpdate.first())

            repository.setAvailableUpdate(null)
            assertNull(repository.availableUpdate.first())
        }

    @Test
    fun `notifiedUpdateVersion defaults empty and round-trips`() =
        testScope.runTest {
            assertEquals("", repository.getNotifiedUpdateVersion())

            repository.setNotifiedUpdateVersion("1.11.0")
            assertEquals("1.11.0", repository.getNotifiedUpdateVersion())
        }

    @Test
    fun `lastAutoCheckAtMillis defaults to zero and round-trips`() =
        testScope.runTest {
            assertEquals(0L, repository.getLastAutoCheckAtMillis())

            repository.setLastAutoCheckAtMillis(123_456_789L)
            assertEquals(123_456_789L, repository.getLastAutoCheckAtMillis())
        }

    @Test
    fun `clearing an already-absent available update leaves it null`() =
        testScope.runTest {
            repository.setAvailableUpdate(null)
            assertNull(repository.availableUpdate.first())
        }
}
