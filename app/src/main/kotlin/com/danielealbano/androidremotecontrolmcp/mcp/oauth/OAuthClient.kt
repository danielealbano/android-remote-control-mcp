package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.serialization.Serializable

/**
 * A persisted OAuth client: a DCR (RFC 7591) registration, or a Client ID Metadata Document (CIMD) client.
 *
 * A DCR client is one connection. A CIMD client id is the app's document URL, shared by every connection of that app,
 * so a CIMD client is one row holding its [connections], each with its own refresh token.
 *
 * @property clientId Server-issued identifier (`arc-<uuid>`) for DCR; the document URL for CIMD
 *   ([OAuthPolicy.isMetadataDocumentClientId]).
 * @property clientName DCR `client_name` (e.g. "Claude"); may be absent. For CIMD the document's `client_name`.
 * @property redirectUris Registered redirect URIs (allowlist-validated at registration); for CIMD the document's.
 * @property applicationType DCR `application_type` (e.g. "web"); shown in the clients row.
 * @property logoUri DCR or document `logo_uri`, as sent; the approval shows it as [OAuthPolicy.displayLogoUri] allows.
 * @property createdAtMs Registration timestamp; for CIMD the first login.
 * @property lastUsedAtMs Last `/mcp` use (debounced) or token grant.
 * @property currentRefreshJti DCR only: the single valid refresh-token jti; null until the first token grant.
 *   Used both for refresh rotation and to protect already-approved clients from registration-spam eviction.
 * @property connections CIMD only: one entry per login, at most [OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT].
 * @property iconUrl The icon the approval screen showed at the latest approval (logo or favicon); null shows initials.
 */
@Serializable
data class OAuthClient(
    val clientId: String,
    val clientName: String? = null,
    val redirectUris: List<String>,
    val applicationType: String? = null,
    val logoUri: String? = null,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val currentRefreshJti: String? = null,
    val connections: List<OAuthConnection> = emptyList(),
    val iconUrl: String? = null,
) {
    /** True once a token was issued: a DCR refresh jti or, for CIMD, a connection. */
    val isApproved: Boolean get() = currentRefreshJti != null || connections.isNotEmpty()
}

/**
 * One login of a CIMD client. Its tokens carry [id], so the connection keeps its own refresh rotation and ends when it
 * is dropped.
 *
 * @property id Server-issued identifier, carried by the connection's tokens.
 * @property refreshJti The connection's single valid refresh-token jti.
 * @property createdAtMs The login.
 * @property lastUsedAtMs Last `/mcp` use (debounced) or token grant; the least recent connection is dropped first.
 */
@Serializable
data class OAuthConnection(
    val id: String,
    val refreshJti: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
)
