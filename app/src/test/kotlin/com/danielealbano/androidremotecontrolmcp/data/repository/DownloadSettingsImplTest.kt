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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DownloadSettingsImpl")
class DownloadSettingsImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val serverLog = RecordingServerLogRepository()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: DownloadSettingsImpl

    @BeforeEach
    fun setUp() {
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "download_settings.preferences_pb") },
            )
        settings = DownloadSettingsImpl(dataStore, SettingsChangeLogger(serverLog, Dispatchers.Unconfined, 0L))
    }

    private fun settingsMessages() = serverLog.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }

    @Test
    fun `keeps the existing DataStore key names`() {
        assertEquals("allow_http_downloads", ALLOW_HTTP_DOWNLOADS_KEY.name)
        assertEquals("allow_unverified_https_certs", ALLOW_UNVERIFIED_HTTPS_KEY.name)
        assertEquals("block_local_network_downloads", BLOCK_LOCAL_NETWORK_DOWNLOADS_KEY.name)
    }

    @Test
    fun `updates allow http downloads and logs the change`() =
        testScope.runTest {
            settings.updateAllowHttpDownloads(true)
            assertEquals(true, dataStore.data.first()[ALLOW_HTTP_DOWNLOADS_KEY])
            settings.updateAllowHttpDownloads(false)
            assertEquals(false, dataStore.data.first()[ALLOW_HTTP_DOWNLOADS_KEY])

            assertEquals(listOf("HTTP downloads allowed", "HTTP downloads disallowed"), settingsMessages())
        }

    @Test
    fun `updates allow unverified https certs and logs the change`() =
        testScope.runTest {
            settings.updateAllowUnverifiedHttpsCerts(true)
            assertEquals(true, dataStore.data.first()[ALLOW_UNVERIFIED_HTTPS_KEY])
            settings.updateAllowUnverifiedHttpsCerts(false)
            assertEquals(false, dataStore.data.first()[ALLOW_UNVERIFIED_HTTPS_KEY])

            assertEquals(
                listOf("Unverified HTTPS certificates allowed", "Unverified HTTPS certificates disallowed"),
                settingsMessages(),
            )
        }

    @Test
    fun `updates block local network downloads and logs the change`() =
        testScope.runTest {
            settings.updateBlockLocalNetworkDownloads(true)
            assertEquals(true, dataStore.data.first()[BLOCK_LOCAL_NETWORK_DOWNLOADS_KEY])
            settings.updateBlockLocalNetworkDownloads(false)
            assertEquals(false, dataStore.data.first()[BLOCK_LOCAL_NETWORK_DOWNLOADS_KEY])

            assertEquals(
                listOf(
                    "Downloads from local network addresses blocked",
                    "Downloads from local network addresses allowed",
                ),
                settingsMessages(),
            )
        }

    @Test
    fun `an unchanged value is not logged`() =
        testScope.runTest {
            settings.updateBlockLocalNetworkDownloads(false)

            assertEquals(emptyList<String>(), settingsMessages())
        }
}
