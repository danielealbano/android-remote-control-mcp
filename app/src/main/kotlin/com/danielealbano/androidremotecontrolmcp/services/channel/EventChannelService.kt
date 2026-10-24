package com.danielealbano.androidremotecontrolmcp.services.channel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ChannelConnectionStatus
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.channel.listeners.NotificationEventListener
import com.danielealbano.androidremotecontrolmcp.services.channel.listeners.WifiEventListener
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EventChannelService : Service() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var eventDispatcher: EventDispatcher

    @Inject
    lateinit var geofenceController: GeofenceChannelController

    @Inject
    lateinit var serverLogRepository: ServerLogRepository

    @Inject
    lateinit var privacyToolGate: PrivacyToolGate

    private var notificationEventListener: NotificationEventListener? = null
    private var wifiEventListener: WifiEventListener? = null

    @Volatile
    private var startLogged = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_GEOFENCE_EVENT -> geofenceController.handleGeofenceIntent(intent)
        }
        return START_STICKY
    }

    private fun handleStart() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        serviceScope.launch {
            val config = settingsRepository.getEventChannelConfig()
            if (config.endpointUrl.isBlank()) {
                Logger.e(TAG, "Cannot start: endpoint URL is empty")
                serverLogRepository.log(ServerLogEntry.Type.CHANNEL, CHANNEL_START_FAILED_LOG_MESSAGE)
                stopSelf()
                return@launch
            }

            eventDispatcher.start(config.endpointUrl, config.authToken)
            startLogged = true
            serverLogRepository.log(ServerLogEntry.Type.CHANNEL, channelStartedLogMessage(config.endpointUrl))

            // Immediate health check on start
            eventDispatcher.healthCheck()

            serviceScope.launch {
                eventDispatcher.connectionStatus.collect { _serviceStatus.value = it }
            }

            // Periodic health check every 30 seconds
            serviceScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(HEALTH_CHECK_INTERVAL_MS)
                    eventDispatcher.healthCheck()
                }
            }

            startListeners(config)
            geofenceController.onChannelStarted(eventDispatcher, serviceScope)

            settingsRepository.eventChannelConfig.collect { newConfig ->
                if (!newConfig.enabled) {
                    handleStop()
                    return@collect
                }
                reconfigureListeners(newConfig)
            }
        }
    }

    private fun handleStop() {
        notificationEventListener?.stop()
        notificationEventListener = null
        wifiEventListener?.stop()
        wifiEventListener = null
        geofenceController.onChannelStopped()
        eventDispatcher.stop()
        _serviceStatus.value = ChannelConnectionStatus.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startListeners(config: EventChannelConfig) {
        if (config.notifications.enabled) {
            notificationEventListener = NotificationEventListener(eventDispatcher, serviceScope, privacyToolGate)
            notificationEventListener?.start(config.notifications)
        }
        if (config.wifi.enabled) {
            wifiEventListener = WifiEventListener(eventDispatcher, serviceScope)
            wifiEventListener?.start(config.wifi, applicationContext)
        }
    }

    private fun reconfigureListeners(config: EventChannelConfig) {
        // Notification listener
        if (config.notifications.enabled && notificationEventListener == null) {
            notificationEventListener = NotificationEventListener(eventDispatcher, serviceScope, privacyToolGate)
            notificationEventListener?.start(config.notifications)
        } else if (!config.notifications.enabled) {
            notificationEventListener?.stop()
            notificationEventListener = null
        } else {
            notificationEventListener?.updateConfig(config.notifications)
        }
        // WiFi listener
        if (config.wifi.enabled && wifiEventListener == null) {
            wifiEventListener = WifiEventListener(eventDispatcher, serviceScope)
            wifiEventListener?.start(config.wifi, applicationContext)
        } else if (!config.wifi.enabled) {
            wifiEventListener?.stop()
            wifiEventListener = null
        } else {
            wifiEventListener?.updateConfig(config.wifi)
        }
        // Geofence config is observed independently by the GeofenceChannelController (gms); nothing to do here.
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Event Channel",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Event Channel active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        // Stop listeners BEFORE cancelling scope — listeners may launch cleanup coroutines
        notificationEventListener?.stop()
        wifiEventListener?.stop()
        geofenceController.onChannelStopped()
        eventDispatcher.stop()
        if (startLogged) {
            serverLogRepository.log(ServerLogEntry.Type.CHANNEL, CHANNEL_STOPPED_LOG_MESSAGE)
            startLogged = false
        }
        serviceScope.cancel()
        _serviceStatus.value = ChannelConnectionStatus.Idle
        Logger.i(TAG, "Event channel service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.danielealbano.androidremotecontrolmcp.channel.START"
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.channel.STOP"
        const val ACTION_GEOFENCE_EVENT = "com.danielealbano.androidremotecontrolmcp.channel.GEOFENCE_EVENT"
        const val EXTRA_GEOFENCE_ZONE_ID = "geofence_zone_id"
        const val EXTRA_GEOFENCE_TRANSITION = "geofence_transition"

        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "event_channel_status"
        private const val TAG = "MCP:EventChannelService"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

        private val _serviceStatus =
            MutableStateFlow<ChannelConnectionStatus>(ChannelConnectionStatus.Idle)
        val serviceStatus: StateFlow<ChannelConnectionStatus> = _serviceStatus.asStateFlow()
    }
}

internal fun channelStartedLogMessage(endpointUrl: String): String = "Event channel started (endpoint: $endpointUrl)"

internal const val CHANNEL_STOPPED_LOG_MESSAGE = "Event channel stopped"

internal const val CHANNEL_START_FAILED_LOG_MESSAGE = "Event channel failed to start: endpoint URL is empty"
