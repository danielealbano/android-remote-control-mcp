package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Filter options for listing installed applications.
 */
enum class AppFilter {
    /** Include all installed applications. */
    ALL,

    /** Include only user-installed applications. */
    USER,

    /** Include only pre-installed system applications. */
    SYSTEM,
}
