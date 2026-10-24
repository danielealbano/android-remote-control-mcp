package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Short-lived foreground service running one enrollment / rotation request. A foreground service keeps the
 * process out of the cached state, so the cached-apps freezer can never suspend an attempt half-way; it stops
 * itself when the last request ends. Without environment extras the current settings are used.
 */
@AndroidEntryPoint
class ArcTunnelEnrollService : Service() {
    @Inject
    lateinit var enrollment: ArcTunnelEnrollment

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ArcTunnelStartTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification())
        val rotate = intent?.getBooleanExtra(EXTRA_ROTATE, false) ?: false
        requests.started(startId)
        scope.launch {
            try {
                val env = intent?.let(::envOf) ?: ArcTunnelEnv.from(settingsRepository.getServerConfig())
                enrollment.execute(env, rotate)
            } finally {
                // Main thread like onStartCommand: the counter and stopSelfResult are never raced.
                withContext(NonCancellable + Dispatchers.Main) { requests.finished()?.let { stopSelfResult(it) } }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory level=$level")
    }

    private fun notification(): Notification =
        NotificationCompat
            .Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_arc_tunnel_enroll_title))
            .setContentText(getString(R.string.notification_arc_tunnel_enroll_body))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    /** The debug-only overrides are honoured in debug builds only, like `ArcTunnelEnv.from`. */
    private fun envOf(intent: Intent): ArcTunnelEnv? =
        intent.getStringExtra(EXTRA_BASE_ADDRESS)?.let {
            ArcTunnelEnv(
                it,
                if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_EDGE_OVERRIDE).orEmpty() else "",
                if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_TRUSTED_CA_PEM).orEmpty() else "",
            )
        }

    companion object {
        private const val TAG = "MCP:ArcTunnelEnrollSvc"
        const val NOTIFICATION_ID = 1005
        private const val EXTRA_BASE_ADDRESS = "base_address"
        private const val EXTRA_EDGE_OVERRIDE = "edge_override"
        private const val EXTRA_TRUSTED_CA_PEM = "trusted_ca_pem"
        private const val EXTRA_ROTATE = "rotate"

        /**
         * Starts the service for [env] (null = the saved settings). False when Android refuses a foreground
         * start from the background (Android 12+): the caller reports it; `am start` of the trampoline
         * activity (`--es action enroll-tunnel`) is the headless way around it.
         */
        fun start(
            context: Context,
            env: ArcTunnelEnv?,
            rotate: Boolean,
        ): Boolean {
            val intent =
                Intent(context, ArcTunnelEnrollService::class.java).apply {
                    putExtra(EXTRA_ROTATE, rotate)
                    if (env != null) {
                        putExtra(EXTRA_BASE_ADDRESS, env.baseAddress)
                        putExtra(EXTRA_EDGE_OVERRIDE, env.edgeOverride)
                        putExtra(EXTRA_TRUSTED_CA_PEM, env.trustedCaPem)
                    }
                }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "foreground service start refused (app in the background)", e)
                false
            }
        }
    }
}
