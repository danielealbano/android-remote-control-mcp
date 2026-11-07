package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.data.model.ShellPatternKind

interface ShellSettings {
    suspend fun updateShellElevationEnabled(enabled: Boolean)

    suspend fun updateShellAutoAllowElevation(enabled: Boolean)

    suspend fun updateShellBlocklist(patterns: List<String>)

    suspend fun updateShellAllowlist(patterns: List<String>)

    suspend fun editShellPatterns(
        kind: ShellPatternKind,
        change: (List<String>) -> List<String>,
    )

    suspend fun updateShellLimit(
        limit: ShellLimit,
        value: Int,
    ): Result<Int>

    suspend fun syncShizukuProvider()
}
