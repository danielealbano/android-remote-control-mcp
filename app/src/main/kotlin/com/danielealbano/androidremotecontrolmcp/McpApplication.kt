package com.danielealbano.androidremotecontrolmcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckScheduler
import com.danielealbano.androidremotecontrolmcp.startup.runFlavorStartupMigrations
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import javax.inject.Inject
import androidx.work.Configuration as WorkConfiguration

@HiltAndroidApp
class McpApplication :
    Application(),
    WorkConfiguration.Provider {
    @Inject
    lateinit var appIconCache: AppIconCache

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // On-demand WorkManager initialization (the default initializer is removed in the manifest) so the
    // Hilt-provided worker factory is used for @HiltWorker construction. Built once (lazy): workerFactory
    // is injected in super.onCreate(), before schedulePeriodic() first touches WorkManager.
    override val workManagerConfiguration: WorkConfiguration by lazy {
        WorkConfiguration
            .Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Flavor-specific one-time migrations (gms: geofence config → dedicated key), launched eagerly
        // on a background coroutine (non-blocking — must never stall onCreate). No-op in foss.
        runFlavorStartupMigrations(this)
        createNotificationChannels()
        configureOsmdroid()
        appIconCache.preload()
        // Apply the one-time auth-model migration eagerly so the UI Flow reflects the migrated model
        // promptly at startup (idempotent; the server start path also guarantees it via getServerConfig()).
        CoroutineScope(Dispatchers.IO).launch { settingsRepository.ensureAuthModelMigrated() }
        UpdateCheckScheduler.schedulePeriodic(this)
        Log.i(TAG, "Application initialized, notification channels created")
    }

    private fun configureOsmdroid() {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        osmConfig.osmdroidBasePath = filesDir
        osmConfig.osmdroidTileCache = cacheDir.resolve("osmdroid")
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val mcpServerChannel =
            NotificationChannel(
                MCP_SERVER_CHANNEL_ID,
                getString(R.string.notification_channel_mcp_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notification for the running MCP server"
            }

        val oauthApprovalChannel =
            NotificationChannel(
                OAUTH_APPROVAL_CHANNEL_ID,
                getString(R.string.notification_channel_oauth_approval_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Heads-up notification for pending OAuth connection approvals"
            }

        val updateChannel =
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                getString(R.string.notification_channel_update_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notification when a newer app version is available"
            }

        val arcTunnelAlertChannel =
            NotificationChannel(
                ARC_TUNNEL_ALERT_CHANNEL_ID,
                getString(R.string.notification_channel_arc_tunnel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alert when a certificate for the free E2E tunnel name was issued to another key"
            }

        notificationManager.createNotificationChannel(mcpServerChannel)
        notificationManager.createNotificationChannel(oauthApprovalChannel)
        notificationManager.createNotificationChannel(updateChannel)
        notificationManager.createNotificationChannel(arcTunnelAlertChannel)
    }

    companion object {
        private const val TAG = "MCP:Application"
        const val MCP_SERVER_CHANNEL_ID = "mcp_server_channel"
        const val OAUTH_APPROVAL_CHANNEL_ID = "oauth_approval_channel"
        const val UPDATE_CHANNEL_ID = "update_channel"
        const val ARC_TUNNEL_ALERT_CHANNEL_ID = "arc_tunnel_alert_channel"
    }
}
