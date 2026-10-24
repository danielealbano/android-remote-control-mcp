package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArcTunnelSettingsImpl")
class ArcTunnelSettingsImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val serverLog = RecordingServerLogRepository()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: ArcTunnelSettingsImpl

    @BeforeEach
    fun setUp() {
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "arc_tunnel_settings.preferences_pb") },
            )
        settings = ArcTunnelSettingsImpl(dataStore, SettingsChangeLogger(serverLog, Dispatchers.Unconfined, 0L))
    }

    private fun settingsMessages() = serverLog.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }

    @Test
    fun `updates arc tunnel base address`() =
        testScope.runTest {
            settings.updateArcTunnelBaseAddress("tunnel.example.org")

            assertEquals("tunnel.example.org", dataStore.data.first()[ARC_TUNNEL_BASE_ADDRESS_KEY])
            assertEquals(
                listOf("Free E2E tunnel base address changed arc-tunnel.io → tunnel.example.org"),
                settingsMessages(),
            )
        }

    @Test
    fun `updates arc tunnel ct check enabled`() =
        testScope.runTest {
            settings.updateArcTunnelCtCheckEnabled(false)

            assertEquals(false, dataStore.data.first()[ARC_TUNNEL_CT_CHECK_ENABLED_KEY])
            assertEquals(listOf("Free E2E tunnel certificate check disabled"), settingsMessages())
        }

    @Test
    fun `updates arc tunnel e2e override`() =
        testScope.runTest {
            settings.updateArcTunnelE2EOverride("10.0.0.1:18443", "pem")

            val prefs = dataStore.data.first()
            assertEquals("10.0.0.1:18443", prefs[ARC_TUNNEL_EDGE_OVERRIDE_KEY])
            assertEquals("pem", prefs[ARC_TUNNEL_TRUSTED_CA_PEM_KEY])
            assertTrue(settingsMessages().isEmpty())
        }
}
