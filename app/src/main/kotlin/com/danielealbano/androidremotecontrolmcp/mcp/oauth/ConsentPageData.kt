package com.danielealbano.androidremotecontrolmcp.mcp.oauth

/**
 * View model for the [consentPageHtml]: the requesting client's identity and the live approval window.
 *
 * @property iconUrl the client's logo embedded as a `data:` URI ([ConsentLogo]), or a public-host favicon resolved
 *   by [ClientIconUrl] (rendered over a CSS monogram fallback) — the favicon is the only external reference, and only
 *   for public hosts.
 * @property loopbackWarning The redirect is loopback, so the page warns before the user approves on the device.
 * @property otherDomainHost A CIMD client's document host on another domain than the redirect, so the page warns.
 */
data class ConsentPageData(
    val approvalId: String,
    val matchCode: String,
    val clientName: String,
    val host: String,
    val expiresInSeconds: Long,
    val iconUrl: String?,
    val loopbackWarning: Boolean = false,
    val otherDomainHost: String? = null,
)
