package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.ShizukuProvider
import javax.inject.Inject

private const val MIN_SHIZUKU_VERSION = 13
private const val NOT_RUNNING_REASON =
    "Shizuku is not running, or has not connected to the app yet (it connects when Shizuku starts and when the app " +
        "process starts; if Shizuku was already running when elevation was turned on, force-stop and reopen the app " +
        "or restart Shizuku)"

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState

    data object NotRunning : ShizukuState

    data object Outdated : ShizukuState

    data object NotGranted : ShizukuState

    data class Ready(
        val uid: Int,
    ) : ShizukuState
}

interface ShizukuStatusProvider {
    fun state(): ShizukuState
}

class ShizukuStatusProviderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ShizukuApi,
    ) : ShizukuStatusProvider {
        override fun state(): ShizukuState {
            val alive = runCatching { api.pingBinder() }.getOrDefault(false)
            return when {
                alive -> connectedState()
                isInstalled() -> ShizukuState.NotRunning
                else -> ShizukuState.NotInstalled
            }
        }

        private fun connectedState(): ShizukuState =
            runCatching {
                when {
                    api.version() < MIN_SHIZUKU_VERSION -> ShizukuState.Outdated
                    !api.isPermissionGranted() -> ShizukuState.NotGranted
                    else -> ShizukuState.Ready(api.uid())
                }
            }.getOrDefault(ShizukuState.NotRunning)

        private fun isInstalled(): Boolean =
            runCatching {
                context.packageManager.getPackageInfo(
                    ShizukuProvider.MANAGER_APPLICATION_ID,
                    PackageManager.PackageInfoFlags.of(0),
                )
            }.isSuccess
    }

internal fun shizukuUnavailableReason(state: ShizukuState): String =
    when (state) {
        ShizukuState.NotInstalled -> "Shizuku is not installed"
        ShizukuState.NotRunning -> NOT_RUNNING_REASON
        ShizukuState.Outdated -> "Shizuku is older than version 13 and must be updated"
        ShizukuState.NotGranted -> "Shizuku has not granted access to the app (grant it in the app's Shell settings)"
        is ShizukuState.Ready -> "Shizuku is ready"
    }
