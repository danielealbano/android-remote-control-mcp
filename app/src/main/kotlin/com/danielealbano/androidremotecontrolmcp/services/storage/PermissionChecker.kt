package com.danielealbano.androidremotecontrolmcp.services.storage

/**
 * Abstracts Android runtime permission checks for testability.
 */
interface PermissionChecker {
    /** Returns true if the given runtime permission is granted. */
    fun hasPermission(permission: String): Boolean
}
