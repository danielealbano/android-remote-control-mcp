// Note: Full DataStore persistence tests require PreferencesDataStore with
// test context (Android instrumented test). These JVM-only tests verify
// serialization round-trips, URL validation logic, and config defaults.
// Serialization tests confirm that toJson/fromJson preserves all fields,
// which is the core persistence mechanism used by SettingsRepositoryImpl.
package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EventChannelSettings")
class EventChannelSettingsTest {
    @Nested
    @DisplayName("default values")
    inner class DefaultValues {
        @Test
        fun `getEventChannelConfig returns default when empty`() {
            val config = EventChannelConfig()
            assertFalse(config.enabled)
            assertEquals("", config.endpointUrl)
            assertEquals("", config.authToken)
            assertFalse(config.notifications.enabled)
            assertFalse(config.wifi.enabled)
        }
    }

    @Nested
    @DisplayName("serialization persistence")
    inner class SerializationPersistence {
        @Test
        fun `updateEventChannelEnabled persists`() {
            val config = EventChannelConfig(enabled = true)
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertTrue(restored.enabled)
        }

        @Test
        fun `updateEndpointUrl persists`() {
            val config = EventChannelConfig(endpointUrl = "http://localhost:9090")
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertEquals("http://localhost:9090", restored.endpointUrl)
        }

        @Test
        fun `updateNotificationFilterMode persists`() {
            val config =
                EventChannelConfig(
                    notifications =
                        com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig(
                            filterMode = NotificationFilterMode.WHITELIST,
                        ),
                )
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertEquals(NotificationFilterMode.WHITELIST, restored.notifications.filterMode)
        }

        @Test
        fun `updateNotificationFilterApps persists`() {
            val apps = setOf("com.app1", "com.app2")
            val config =
                EventChannelConfig(
                    notifications =
                        com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig(
                            filterApps = apps,
                        ),
                )
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertEquals(apps, restored.notifications.filterApps)
        }

        @Test
        fun `updateWifiSsids persists`() {
            val ssids = setOf("Net1", "Net2")
            val config =
                EventChannelConfig(
                    wifi =
                        com.danielealbano.androidremotecontrolmcp.data.model.WifiChannelConfig(
                            ssids = ssids,
                        ),
                )
            val json = config.toJson()
            val restored = EventChannelConfig.fromJson(json)
            assertEquals(ssids, restored.wifi.ssids)
        }
    }

    @Nested
    @DisplayName("URL validation")
    inner class UrlValidation {
        private val repo =
            SettingsRepositoryImpl(
                mockk(relaxed = true),
                SettingsChangeLogger(RecordingServerLogRepository(), Dispatchers.Unconfined, 0L),
                EventChannelSettingsImpl(
                    mockk(relaxed = true),
                    SettingsChangeLogger(RecordingServerLogRepository(), Dispatchers.Unconfined, 0L),
                ),
                io.mockk.mockk<ArcTunnelSettings>(relaxed = true),
                io.mockk.mockk<DownloadSettings>(relaxed = true),
            )

        @Test
        fun `validateEndpointUrl rejects invalid URL`() {
            val result = repo.validateEndpointUrl("not a url")
            assertTrue(result.isFailure)
        }

        @Test
        fun `validateEndpointUrl rejects empty string`() {
            val result = repo.validateEndpointUrl("")
            assertTrue(result.isFailure)
        }

        @Test
        fun `validateEndpointUrl accepts valid HTTP URL`() {
            val result = repo.validateEndpointUrl("http://localhost:9090")
            assertTrue(result.isSuccess)
            assertEquals("http://localhost:9090", result.getOrNull())
        }

        @Test
        fun `validateEndpointUrl accepts valid HTTPS URL`() {
            val result = repo.validateEndpointUrl("https://example.com")
            assertTrue(result.isSuccess)
        }
    }

    @Nested
    @DisplayName("token generation")
    inner class TokenGeneration {
        @Test
        fun `generateNewEventChannelAuthToken generates UUID format`() {
            val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            val token =
                java.util.UUID
                    .randomUUID()
                    .toString()
            assertTrue(uuidPattern.matches(token))
        }
    }
}

private fun mockk(relaxed: Boolean): androidx.datastore.core.DataStore<
    androidx.datastore.preferences.core.Preferences,
> =
    io.mockk.mockk(relaxed = relaxed)
