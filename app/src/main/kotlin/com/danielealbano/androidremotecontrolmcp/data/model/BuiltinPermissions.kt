package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Permission overrides for a built-in storage location.
 * Persisted in DataStore as JSON. Missing entries default to read-only.
 */
data class BuiltinPermissions(
    val allowWrite: Boolean = false,
    val allowDelete: Boolean = false,
)
