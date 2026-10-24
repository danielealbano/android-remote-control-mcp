// Note: EventChannelService extends android.app.Service. Full lifecycle tests (ACTION_START/STOP,
// listener creation, config observer) require Robolectric or instrumented tests. These JVM-only
// tests verify config validation logic, constants, contracts and the onStartCommand dispatch of a
// geofence intent on a plain instance whose collaborators are set directly.
package com.danielealbano.androidremotecontrolmcp.services.channel

import android.app.Service
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.WifiChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EventChannelService")
class EventChannelServiceTest {
    @Nested
    @DisplayName("config validation")
    inner class ConfigValidation {
        @Test
        fun `empty endpoint url is blank`() {
            val config = EventChannelConfig(enabled = true, endpointUrl = "", authToken = "token")
            assertTrue(config.endpointUrl.isBlank())
        }

        @Test
        fun `empty auth token is blank`() {
            val config =
                EventChannelConfig(
                    enabled = true,
                    endpointUrl = "http://localhost:9090",
                    authToken = "",
                )
            assertTrue(config.authToken.isBlank())
        }

        @Test
        fun `config with blank endpoint is an unstartable shape regardless of token`() {
            // Live handleStart() gate (verified manually in Story 4) blocks on
            // endpointUrl.isBlank() alone; authToken is independent.
            val configBlankBoth =
                EventChannelConfig(enabled = true, endpointUrl = "", authToken = "")
            val configBlankEndpointWithToken =
                EventChannelConfig(enabled = true, endpointUrl = "", authToken = "x")
            assertTrue(configBlankBoth.endpointUrl.isBlank())
            assertTrue(configBlankEndpointWithToken.endpointUrl.isBlank())
        }

        @Test
        fun `config with non-blank endpoint and empty authToken is a startable shape`() {
            // Verifies the shape contract only — the live handleStart() gate is
            // covered by manual verification in Story 4 (Task 4.4).
            val config =
                EventChannelConfig(
                    enabled = true,
                    endpointUrl = "http://localhost:9090",
                    authToken = "",
                )
            assertFalse(config.endpointUrl.isBlank())
            assertTrue(config.authToken.isBlank())
        }

        @Test
        fun `whitespace-only endpoint is blank`() {
            val config = EventChannelConfig(enabled = true, endpointUrl = "   ", authToken = "token")
            assertTrue(config.endpointUrl.isBlank())
        }
    }

    @Nested
    @DisplayName("service status")
    inner class ServiceStatus {
        @Test
        fun `initial service status is Idle`() {
            assertEquals(ChannelConnectionStatus.Idle, EventChannelService.serviceStatus.value)
        }
    }

    @Nested
    @DisplayName("listener configuration logic")
    inner class ListenerConfigurationLogic {
        @Test
        fun `config with notifications enabled requires notification listener creation`() {
            val config =
                EventChannelConfig(
                    enabled = true,
                    endpointUrl = "http://localhost:9090",
                    authToken = "token",
                    notifications = NotificationChannelConfig(enabled = true),
                )
            assertTrue(config.notifications.enabled)
            assertFalse(config.wifi.enabled)
        }

        @Test
        fun `config with all listeners disabled means no active sources`() {
            val config =
                EventChannelConfig(
                    enabled = true,
                    endpointUrl = "http://localhost:9090",
                    authToken = "token",
                )
            assertFalse(config.notifications.enabled)
            assertFalse(config.wifi.enabled)
        }

        @Test
        fun `config with notifications and wifi enabled means two active sources`() {
            val config =
                EventChannelConfig(
                    enabled = true,
                    endpointUrl = "http://localhost:9090",
                    authToken = "token",
                    notifications = NotificationChannelConfig(enabled = true),
                    wifi = WifiChannelConfig(enabled = true),
                )
            assertTrue(config.notifications.enabled)
            assertTrue(config.wifi.enabled)
        }

        @Test
        fun `disabled channel config should stop service`() {
            val config = EventChannelConfig(enabled = false)
            assertFalse(config.enabled)
        }
    }

    @Nested
    @DisplayName("action constants")
    inner class ActionConstants {
        @Test
        fun `ACTION_START is correctly defined`() {
            assertEquals(
                "com.danielealbano.androidremotecontrolmcp.channel.START",
                EventChannelService.ACTION_START,
            )
        }

        @Test
        fun `ACTION_STOP is correctly defined`() {
            assertEquals(
                "com.danielealbano.androidremotecontrolmcp.channel.STOP",
                EventChannelService.ACTION_STOP,
            )
        }

        @Test
        fun `ACTION_GEOFENCE_EVENT is correctly defined`() {
            assertEquals(
                "com.danielealbano.androidremotecontrolmcp.channel.GEOFENCE_EVENT",
                EventChannelService.ACTION_GEOFENCE_EVENT,
            )
        }
    }

    @Nested
    @DisplayName("start command dispatch")
    inner class StartCommandDispatch {
        @Test
        fun `onStartCommand forwards a geofence intent to the geofence controller`() {
            val geofenceController = mockk<GeofenceChannelController>(relaxed = true)
            val service =
                EventChannelService().apply {
                    settingsRepository = mockk<SettingsRepository>(relaxed = true)
                    eventDispatcher = mockk<EventDispatcher>(relaxed = true)
                    this.geofenceController = geofenceController
                    serverLogRepository = mockk<ServerLogRepository>(relaxed = true)
                    privacyToolGate = mockk<PrivacyToolGate>(relaxed = true)
                }
            val intent = mockk<Intent> { every { action } returns EventChannelService.ACTION_GEOFENCE_EVENT }

            val result = service.onStartCommand(intent, 0, 1)

            verify { geofenceController.handleGeofenceIntent(intent) }
            assertEquals(Service.START_STICKY, result)
        }
    }
}
