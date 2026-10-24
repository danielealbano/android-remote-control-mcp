package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.GeofenceZone
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GeofenceConfigRepository")
class GeofenceConfigRepositoryTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: GeofenceConfigRepositoryImpl

    private var testFileCounter = 0

    private val eventChannelKey = stringPreferencesKey("event_channel_config")

    private val zone =
        GeofenceZone(
            id = "z1",
            name = "Home",
            latitude = 40.7128,
            longitude = -74.006,
            radiusMeters = 200f,
        )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        testFileCounter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "test_geofence_$testFileCounter.preferences_pb") },
            )
        repository = GeofenceConfigRepositoryImpl(dataStore)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private suspend fun seedLegacyBlob(json: String) {
        dataStore.edit { it[eventChannelKey] = json }
    }

    @Test
    fun `round-trips geofence config`() =
        runTest(testDispatcher) {
            repository.updateGeofenceChannelEnabled(true)
            repository.addGeofenceZone(zone)

            val stored = repository.getGeofenceConfig()
            assertTrue(stored.enabled)
            assertEquals(1, stored.zones.size)
            assertEquals(zone, stored.zones.first())

            repository.updateGeofenceZone(zone.copy(name = "Office"))
            assertEquals(
                "Office",
                repository
                    .getGeofenceConfig()
                    .zones
                    .first()
                    .name,
            )

            repository.removeGeofenceZone("z1")
            assertTrue(repository.getGeofenceConfig().zones.isEmpty())
        }

    @Test
    fun `migration copies legacy zones once`() =
        runTest(testDispatcher) {
            seedLegacyBlob(
                """{"enabled":true,"endpointUrl":"http://x","geofence":{"enabled":true,""" +
                    """"zones":[{"id":"z1","name":"Home","latitude":40.7128,"longitude":-74.006,""" +
                    """"radiusMeters":200.0}]}}""",
            )

            repository.migrateIfNeeded()

            val migrated = repository.getGeofenceConfig()
            assertTrue(migrated.enabled)
            assertEquals(1, migrated.zones.size)
            assertEquals("z1", migrated.zones.first().id)
        }

    @Test
    fun `migration is idempotent`() =
        runTest(testDispatcher) {
            seedLegacyBlob(
                """{"geofence":{"enabled":true,"zones":[{"id":"z1","name":"Home",""" +
                    """"latitude":40.7128,"longitude":-74.006,"radiusMeters":200.0}]}}""",
            )

            repository.migrateIfNeeded()
            // Second call must not re-migrate / duplicate.
            repository.migrateIfNeeded()

            assertEquals(1, repository.getGeofenceConfig().zones.size)
        }

    @Test
    fun `migration tolerates absent legacy blob`() =
        runTest(testDispatcher) {
            repository.migrateIfNeeded()

            val config = repository.getGeofenceConfig()
            assertFalse(config.enabled)
            assertTrue(config.zones.isEmpty())
        }

    @Test
    fun `migration tolerates malformed legacy blob`() =
        runTest(testDispatcher) {
            seedLegacyBlob("not json {{{")

            repository.migrateIfNeeded()

            val config = repository.getGeofenceConfig()
            assertFalse(config.enabled)
            assertTrue(config.zones.isEmpty())
        }

    @Test
    fun `legacy zones survive a main event-channel write after migration`() =
        runTest(testDispatcher) {
            // Seed a legacy blob carrying a geofence zone, then migrate it to the dedicated key.
            seedLegacyBlob(
                """{"enabled":false,"geofence":{"enabled":true,"zones":[{"id":"z1","name":"Home",""" +
                    """"latitude":40.7128,"longitude":-74.006,"radiusMeters":200.0}]}}""",
            )
            repository.migrateIfNeeded()
            assertEquals(1, repository.getGeofenceConfig().zones.size)

            // A main event-channel write rewrites the shared blob WITHOUT the geofence field.
            val changeLogger = SettingsChangeLogger(RecordingServerLogRepository(), Dispatchers.Unconfined, 0L)
            val settingsRepository =
                SettingsRepositoryImpl(
                    dataStore,
                    changeLogger,
                    EventChannelSettingsImpl(dataStore, changeLogger),
                    ArcTunnelSettingsImpl(dataStore, changeLogger),
                    DownloadSettingsImpl(dataStore, changeLogger),
                )
            settingsRepository.updateNotificationChannelEnabled(true)

            // The dedicated geofence key must be untouched — the zone survives.
            assertEquals(1, repository.getGeofenceConfig().zones.size)
            assertEquals(
                "z1",
                repository
                    .getGeofenceConfig()
                    .zones
                    .first()
                    .id,
            )
        }
}
