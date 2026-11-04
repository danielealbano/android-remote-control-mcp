package com.danielealbano.androidremotecontrolmcp.e2e

/** What the OAuth e2e flows share. */
internal object OAuthE2EVectors {
    // RFC 7636 Appendix B PKCE test vector.
    const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    val APPROVAL_ID_REGEX = Regex("""var id = "([^"]+)"""")
    val CODE_REGEX = Regex("""[?&]code=([^&]+)""")
}
