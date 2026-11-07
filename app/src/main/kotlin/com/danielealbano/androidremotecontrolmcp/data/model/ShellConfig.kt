package com.danielealbano.androidremotecontrolmcp.data.model

private const val DEFAULT_MAX_RUNNING = 16
private const val UPPER_MAX_RUNNING = 64
private const val DEFAULT_MAX_FINISHED = 32
private const val UPPER_MAX_FINISHED = 256
private const val DEFAULT_BACKGROUND_DEFAULT_MINUTES = 30
private const val DEFAULT_BACKGROUND_MAX_MINUTES = 120
private const val UPPER_BACKGROUND_MINUTES = 1440
private const val DEFAULT_APPROVAL_SECONDS = 60
private const val LOWER_APPROVAL_SECONDS = 10
private const val UPPER_APPROVAL_SECONDS = 85
private const val DEFAULT_FOREGROUND_SECONDS = 90
private const val LOWER_FOREGROUND_SECONDS = 10
private const val UPPER_FOREGROUND_SECONDS = 90
private const val DEFAULT_OUTPUT_PER_CALL_KIB = 64
private const val LOWER_OUTPUT_PER_CALL_KIB = 4
private const val UPPER_OUTPUT_PER_CALL_KIB = 256
private const val DEFAULT_OUTPUT_BUFFER_KIB = 1024
private const val LOWER_OUTPUT_BUFFER_KIB = 64
private const val UPPER_OUTPUT_BUFFER_KIB = 16384
private const val KIB_PER_MIB = 1024

/** Output kept in memory, in all: (running + finished kept) × output kept per process. */
const val SHELL_OUTPUT_MEMORY_CAP_MIB = 64
const val SHELL_OUTPUT_MEMORY_CAP_KIB = SHELL_OUTPUT_MEMORY_CAP_MIB * KIB_PER_MIB

enum class ShellLimit(
    val key: String,
    val defaultValue: Int,
    val min: Int,
    val max: Int,
) {
    MAX_RUNNING_PROCESSES("shell_max_running_processes", DEFAULT_MAX_RUNNING, 1, UPPER_MAX_RUNNING),
    MAX_FINISHED_PROCESSES("shell_max_finished_processes", DEFAULT_MAX_FINISHED, 0, UPPER_MAX_FINISHED),
    BACKGROUND_TIMEOUT_DEFAULT_MINUTES(
        "shell_background_timeout_default_minutes",
        DEFAULT_BACKGROUND_DEFAULT_MINUTES,
        1,
        UPPER_BACKGROUND_MINUTES,
    ),
    BACKGROUND_TIMEOUT_MAX_MINUTES(
        "shell_background_timeout_max_minutes",
        DEFAULT_BACKGROUND_MAX_MINUTES,
        1,
        UPPER_BACKGROUND_MINUTES,
    ),
    APPROVAL_TIMEOUT_SECONDS(
        "shell_approval_timeout_seconds",
        DEFAULT_APPROVAL_SECONDS,
        LOWER_APPROVAL_SECONDS,
        UPPER_APPROVAL_SECONDS,
    ),
    FOREGROUND_WAIT_SECONDS(
        "shell_foreground_wait_seconds",
        DEFAULT_FOREGROUND_SECONDS,
        LOWER_FOREGROUND_SECONDS,
        UPPER_FOREGROUND_SECONDS,
    ),
    OUTPUT_PER_CALL_KIB(
        "shell_output_per_call_kib",
        DEFAULT_OUTPUT_PER_CALL_KIB,
        LOWER_OUTPUT_PER_CALL_KIB,
        UPPER_OUTPUT_PER_CALL_KIB,
    ),
    OUTPUT_BUFFER_KIB(
        "shell_output_buffer_kib",
        DEFAULT_OUTPUT_BUFFER_KIB,
        LOWER_OUTPUT_BUFFER_KIB,
        UPPER_OUTPUT_BUFFER_KIB,
    ),
    ;

    fun isValid(value: Int): Boolean = value in min..max
}

/** The limits whose combination [SHELL_OUTPUT_MEMORY_CAP_KIB] caps. */
val SHELL_OUTPUT_MEMORY_LIMITS: Set<ShellLimit> =
    setOf(ShellLimit.MAX_RUNNING_PROCESSES, ShellLimit.MAX_FINISHED_PROCESSES, ShellLimit.OUTPUT_BUFFER_KIB)

enum class ShellPatternKind {
    BLOCKLIST,
    ALLOWLIST,
}

data class ShellConfig(
    val elevationEnabled: Boolean = false,
    val autoAllowElevation: Boolean = false,
    val blocklist: List<String> = emptyList(),
    val allowlist: List<String> = emptyList(),
    val limits: Map<ShellLimit, Int> = emptyMap(),
) {
    fun limit(limit: ShellLimit): Int = limits[limit] ?: limit.defaultValue

    val backgroundTimeoutDefaultMinutes: Int
        get() =
            minOf(
                limit(ShellLimit.BACKGROUND_TIMEOUT_DEFAULT_MINUTES),
                limit(ShellLimit.BACKGROUND_TIMEOUT_MAX_MINUTES),
            )

    /** The output memory, in KiB, the limits allow with [limit] set to [value]. */
    fun outputMemoryKib(
        limit: ShellLimit,
        value: Int,
    ): Long {
        val next = copy(limits = limits + (limit to value))
        val running = next.limit(ShellLimit.MAX_RUNNING_PROCESSES).toLong()
        val finished = next.limit(ShellLimit.MAX_FINISHED_PROCESSES)
        return (running + finished) * next.limit(ShellLimit.OUTPUT_BUFFER_KIB)
    }
}
