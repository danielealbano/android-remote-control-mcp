package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LogoUrlPolicy")
class LogoUrlPolicyTest {
    @Test
    @DisplayName("accepts an ordinary https host")
    fun acceptsOrdinaryHttps() {
        assertTrue(LogoUrlPolicy.isSafeLogoUrl("https://cdn.example.com/logo.png"))
    }

    @Test
    @DisplayName("rejects non-https")
    fun rejectsNonHttps() {
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("http://cdn.example.com/logo.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl(null))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("not a url"))
    }

    @Test
    @DisplayName("rejects localhost and private/loopback/link-local hosts")
    fun rejectsPrivateHosts() {
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://localhost/x.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://127.0.0.1/x.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://10.0.0.5/x.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://172.16.0.1/x.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://192.168.1.1/x.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://169.254.1.1/x.png"))
        assertFalse(LogoUrlPolicy.isSafeLogoUrl("https://[::1]/x.png"))
    }

    @Test
    @DisplayName("rejects every IP-literal host, public ones too: the loaders' DNS filter never sees them")
    fun rejectsIpLiterals() {
        listOf(
            "https://8.8.8.8/x.png",
            "https://[::ffff:192.168.1.1]:8443/x.png",
            "https://0.0.0.0:8080/x.png",
            "https://100.64.0.1/x.png",
            "https://192.0.0.8/x.png",
            "https://[2606:4700::1]/x.png",
        ).forEach { assertFalse(LogoUrlPolicy.isSafeLogoUrl(it), it) }
        assertTrue(LogoUrlPolicy.isIpLiteral("10.0.0.1"))
        assertTrue(LogoUrlPolicy.isIpLiteral("::1"))
        assertFalse(LogoUrlPolicy.isIpLiteral("cdn.example.com"))
        assertFalse(LogoUrlPolicy.isIpLiteral(null))
    }
}
