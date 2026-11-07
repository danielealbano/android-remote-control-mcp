package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ShellPatternKind
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuApi
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuState
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuStatusProvider
import com.danielealbano.androidremotecontrolmcp.services.shell.SuProbeResult
import com.danielealbano.androidremotecontrolmcp.services.shell.SuShell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

private const val TAG = "MCP:ShellSettingsVM"
private const val FLOW_TIMEOUT_MS = 5_000L
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 6801

enum class ShellSwitch {
    ELEVATION,
    AUTO_ALLOW,
}

sealed interface SuStatus {
    val path: String?

    data object NotFound : SuStatus {
        override val path: String? = null
    }

    data class Found(
        override val path: String,
    ) : SuStatus

    data class Testing(
        override val path: String,
    ) : SuStatus

    data class Works(
        override val path: String,
    ) : SuStatus

    data class Fails(
        override val path: String,
        val reason: String,
    ) : SuStatus
}

@HiltViewModel
class ShellSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val shizukuStatus: ShizukuStatusProvider,
        private val shizukuApi: ShizukuApi,
        private val su: SuShell,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val shellConfig: StateFlow<ShellConfig> =
            settingsRepository.serverConfig
                .map { it.shellConfig }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), ShellConfig())

        private val limitEditor =
            ShellLimitEditor { limit, value -> settingsRepository.updateShellLimit(limit, value).isSuccess }
        val limitFields: StateFlow<Map<ShellLimit, ShellLimitField>> = limitEditor.fields

        private val limitSaves = Channel<Pair<ShellLimit, Int>>(Channel.UNLIMITED)

        private val mutableShizukuState = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
        val shizukuState: StateFlow<ShizukuState> = mutableShizukuState.asStateFlow()

        private val mutableSuStatus = MutableStateFlow<SuStatus>(SuStatus.NotFound)
        val suStatus: StateFlow<SuStatus> = mutableSuStatus.asStateFlow()

        private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshStatus() }

        init {
            shizukuApi.addPermissionResultListener(permissionListener)
            viewModelScope.launch(ioDispatcher) {
                for ((limit, value) in limitSaves) limitEditor.save(limit, value)
            }
            refresh()
        }

        fun updateLimit(
            limit: ShellLimit,
            text: String,
        ) {
            limitEditor.edit(limit, text)?.let { limitSaves.trySend(limit to it) }
        }

        fun setSwitch(
            which: ShellSwitch,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                when (which) {
                    ShellSwitch.ELEVATION -> settingsRepository.updateShellElevationEnabled(enabled)
                    ShellSwitch.AUTO_ALLOW -> settingsRepository.updateShellAutoAllowElevation(enabled)
                }
            }
        }

        fun addPattern(
            kind: ShellPatternKind,
            pattern: String,
        ) {
            val trimmed = pattern.trim()
            if (trimmed.isNotEmpty()) editPatterns(kind) { it + trimmed }
        }

        fun removePattern(
            kind: ShellPatternKind,
            pattern: String,
        ) = editPatterns(kind) { it - pattern }

        fun refresh() {
            viewModelScope.launch(ioDispatcher) { limitEditor.load(settingsRepository.getServerConfig().shellConfig) }
            refreshStatus()
        }

        fun requestShizukuPermission() {
            runCatching { shizukuApi.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
                .onFailure { Log.w(TAG, "Shizuku permission request failed", it) }
        }

        fun testSu() {
            viewModelScope.launch(ioDispatcher) {
                val path = su.locate()
                if (path == null) {
                    mutableSuStatus.value = SuStatus.NotFound
                    return@launch
                }
                mutableSuStatus.value = SuStatus.Testing(path)
                mutableSuStatus.value =
                    when (val result = su.probe()) {
                        SuProbeResult.Works -> SuStatus.Works(path)
                        is SuProbeResult.Fails -> SuStatus.Fails(path, result.reason)
                    }
            }
        }

        override fun onCleared() {
            shizukuApi.removePermissionResultListener(permissionListener)
            limitSaves.close()
        }

        private fun refreshStatus() {
            viewModelScope.launch(ioDispatcher) {
                mutableShizukuState.value = shizukuStatus.state()
                val path = su.locate()
                mutableSuStatus.update { current ->
                    when {
                        path == null -> SuStatus.NotFound
                        current.path == path -> current
                        else -> SuStatus.Found(path)
                    }
                }
            }
        }

        private fun editPatterns(
            kind: ShellPatternKind,
            change: (List<String>) -> List<String>,
        ) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.editShellPatterns(kind, change) }
        }
    }
