package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.SHELL_OUTPUT_MEMORY_LIMITS
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ShellLimitInputError {
    REQUIRED,
    NOT_A_NUMBER,
    OUT_OF_RANGE,
    ABOVE_MAX,
    BELOW_DEFAULT,
    OVER_MEMORY_CAP,
}

data class ShellLimitField(
    val text: String,
    val error: ShellLimitInputError? = null,
)

class ShellLimitEditor(
    private val store: suspend (ShellLimit, Int) -> Boolean,
) {
    private val mutableFields = MutableStateFlow<Map<ShellLimit, ShellLimitField>>(emptyMap())
    val fields: StateFlow<Map<ShellLimit, ShellLimitField>> = mutableFields.asStateFlow()

    fun load(config: ShellConfig) {
        mutableFields.value = ShellLimit.entries.associateWith { ShellLimitField(config.limit(it).toString()) }
    }

    fun edit(
        limit: ShellLimit,
        text: String,
    ): Int? {
        val value = text.trim().toIntOrNull()
        val error =
            when {
                text.isBlank() -> ShellLimitInputError.REQUIRED
                value == null -> ShellLimitInputError.NOT_A_NUMBER
                !limit.isValid(value) -> ShellLimitInputError.OUT_OF_RANGE
                else -> null
            }
        set(limit, ShellLimitField(text, error))
        return value.takeIf { error == null }
    }

    suspend fun save(
        limit: ShellLimit,
        value: Int,
    ) {
        if (store(limit, value)) {
            siblings(limit).forEach { retry(it) }
        } else {
            markIfUnchanged(limit, value, refusedError(limit))
        }
    }

    private suspend fun retry(limit: ShellLimit) {
        val field = fields.value[limit]
        val value = field?.text?.trim()?.toIntOrNull()
        if (field?.error == refusedError(limit) && value != null && store(limit, value)) {
            markIfUnchanged(limit, value, null)
        }
    }

    private fun markIfUnchanged(
        limit: ShellLimit,
        value: Int,
        error: ShellLimitInputError?,
    ) {
        mutableFields.update { current ->
            val latest = current[limit]
            if (latest != null && latest.text.trim().toIntOrNull() == value) {
                current + (limit to latest.copy(error = error))
            } else {
                current
            }
        }
    }

    private fun set(
        limit: ShellLimit,
        field: ShellLimitField,
    ) {
        mutableFields.update { it + (limit to field) }
    }

    private fun siblings(limit: ShellLimit): List<ShellLimit> =
        when (limit) {
            ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES -> listOf(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES)
            ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES -> listOf(ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES)
            in SHELL_OUTPUT_MEMORY_LIMITS -> (SHELL_OUTPUT_MEMORY_LIMITS - limit).toList()
            else -> emptyList()
        }

    private fun refusedError(limit: ShellLimit): ShellLimitInputError =
        when (limit) {
            ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES -> ShellLimitInputError.ABOVE_MAX
            ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES -> ShellLimitInputError.BELOW_DEFAULT
            in SHELL_OUTPUT_MEMORY_LIMITS -> ShellLimitInputError.OVER_MEMORY_CAP
            else -> ShellLimitInputError.OUT_OF_RANGE
        }
}
