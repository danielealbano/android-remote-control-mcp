package com.danielealbano.androidremotecontrolmcp.data.repository

/**
 * The `download_from_url` switches. Part of [SettingsRepository] (which extends this interface); the values are read
 * through [com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig].
 */
interface DownloadSettings {
    /** Updates whether HTTP (non-HTTPS) downloads are allowed. */
    suspend fun updateAllowHttpDownloads(enabled: Boolean)

    /** Updates whether unverified HTTPS certificates are accepted for downloads. */
    suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean)

    /** Updates whether downloads refuse to connect to local network addresses. */
    suspend fun updateBlockLocalNetworkDownloads(enabled: Boolean)
}
