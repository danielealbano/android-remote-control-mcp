package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only [BroadcastReceiver] that accepts test configuration overrides
 * via `adb shell am broadcast`.
 *
 * This receiver is ONLY included in debug builds. It allows E2E tests to
 * inject server settings (bearer token, binding address, port) into the
 * app's DataStore without manipulating protobuf files directly.
 *
 * **Usage** (from E2E test via adb):
 * ```
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE \
 *   -n com.danielealbano.androidremotecontrolmcp.debug/.debug.E2EConfigReceiver \
 *   --es bearer_token "test-token-uuid" \
 *   --es binding_address "0.0.0.0" \
 *   --ei port 8080
 * ```
 */
@AndroidEntryPoint
class E2EConfigReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_E2E_CONFIGURE) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        Log.i(TAG, "Received E2E configuration broadcast")

        val bearerToken = intent.getStringExtra(EXTRA_BEARER_TOKEN)
        val bindingAddress = intent.getStringExtra(EXTRA_BINDING_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, -1)

        scope.launch {
            if (!bearerToken.isNullOrEmpty()) {
                settingsRepository.updateBearerToken(bearerToken)
                Log.i(TAG, "Bearer token updated (length=${bearerToken.length})")
            }
            if (!bindingAddress.isNullOrEmpty()) {
                val address =
                    if (bindingAddress == "0.0.0.0") {
                        BindingAddress.NETWORK
                    } else {
                        BindingAddress.LOCALHOST
                    }
                settingsRepository.updateBindingAddress(address)
                Log.i(TAG, "Binding address updated to $address")
            }
            if (port in 1..65535) {
                settingsRepository.updatePort(port)
                Log.i(TAG, "Port updated to $port")
            }
            Log.i(TAG, "E2E configuration applied successfully")
        }
    }

    companion object {
        private const val TAG = "E2E:ConfigReceiver"
        const val ACTION_E2E_CONFIGURE = "com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE"
        private const val EXTRA_BEARER_TOKEN = "bearer_token"
        private const val EXTRA_BINDING_ADDRESS = "binding_address"
        private const val EXTRA_PORT = "port"
    }
}
