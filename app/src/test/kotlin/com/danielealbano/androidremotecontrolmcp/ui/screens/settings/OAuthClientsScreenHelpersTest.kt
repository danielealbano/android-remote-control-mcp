package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import com.danielealbano.androidremotecontrolmcp.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthClientsScreen helpers")
class OAuthClientsScreenHelpersTest {
    @Test
    @DisplayName("revoking a CIMD row says every connection of the app ends; a DCR row keeps the client text")
    fun revokeDialogBody() {
        assertEquals(
            R.string.oauth_clients_revoke_dialog_body_app,
            revokeDialogBodyRes("https://chatgpt.com/oauth/client.json"),
        )
        assertEquals(R.string.oauth_clients_revoke_dialog_body, revokeDialogBodyRes("arc-1234"))
    }
}
