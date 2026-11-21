package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.geo.GeoIpResolver

/**
 * Groups the OAuth collaborators passed into [com.danielealbano.androidremotecontrolmcp.mcp.McpServer]
 * so its constructor stays within detekt's `LongParameterList` threshold (no `@Suppress` needed).
 */
class OAuthServerDeps(
    val jwtTokenService: JwtTokenService,
    val oauthClientRepository: OAuthClientRepository,
    val authorizationCodeStore: AuthorizationCodeStore,
    val approvalCoordinator: OAuthApprovalCoordinator,
    val geoIpResolver: GeoIpResolver,
    val httpFetcher: OAuthHttpFetcher,
)
