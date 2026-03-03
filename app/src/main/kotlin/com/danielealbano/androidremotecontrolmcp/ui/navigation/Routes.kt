package com.danielealbano.androidremotecontrolmcp.ui.navigation

sealed class TopLevelRoute(val route: String) {
    data object Server : TopLevelRoute("server")
    data object Settings : TopLevelRoute("settings")
    data object About : TopLevelRoute("about")
}

sealed class SettingsRoute(val route: String) {
    data object Index : SettingsRoute("settings/index")
    data object General : SettingsRoute("settings/general")
    data object Security : SettingsRoute("settings/security")
    data object Tunnel : SettingsRoute("settings/tunnel")
    data object McpTools : SettingsRoute("settings/mcp_tools")
    data object Permissions : SettingsRoute("settings/permissions")
    data object Storage : SettingsRoute("settings/storage")
}
