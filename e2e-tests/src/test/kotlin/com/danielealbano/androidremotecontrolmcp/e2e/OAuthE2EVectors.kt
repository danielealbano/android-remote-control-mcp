package com.danielealbano.androidremotecontrolmcp.e2e

import java.net.URLDecoder

/** What the OAuth e2e flows share. */
internal object OAuthE2EVectors {
    // RFC 7636 Appendix B PKCE test vector.
    const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    val APPROVAL_ID_REGEX = Regex("""var id = "([^"]+)"""")
    val CODE_REGEX = Regex("""[?&]code=([^&]+)""")
    private val ISS_REGEX = Regex("""[?&]iss=([^&]+)""")

    /** The decoded RFC 9207 `iss` of an authorization redirect, or null when it has none. */
    fun issOf(redirect: String): String? =
        ISS_REGEX
            .find(redirect)
            ?.groupValues
            ?.get(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
}
