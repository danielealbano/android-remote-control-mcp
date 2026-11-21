package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth consent page")
class ConsentPageTest {
    private fun data(
        clientName: String = "Claude",
        host: String = "claude.ai",
        expiresInSeconds: Long = 300L,
        iconUrl: String? = null,
    ) = ConsentPageData("approval-id", "42", clientName, host, expiresInSeconds, iconUrl)

    @Test
    @DisplayName("warns about another domain, escaped, and shows only the loopback warning for a loopback redirect")
    fun otherDomainWarning() {
        val base = ConsentPageData("approval-id", "42", "App", "app.example", 300L, null)
        val other = consentPageHtml(base.copy(otherDomainHost = "<b>docs.example</b>"))
        val escaped = "This app identifies itself from &lt;b&gt;docs.example&lt;/b&gt;, a different domain."
        assertTrue(other.contains(escaped), other)
        assertFalse(other.contains("<b>docs.example</b>"), other)
        val loopback = consentPageHtml(base.copy(loopbackWarning = true, otherDomainHost = "docs.example"))
        assertTrue(loopback.contains("The access goes to an app on the computer where this page is open."), loopback)
        assertFalse(loopback.contains("a different domain"), loopback)
        assertFalse(consentPageHtml(base).contains("class=\"warning\""))
    }

    @Test
    @DisplayName("respondConsentPage passes the approval's other-domain host to the page")
    fun respondConsentPagePassesOtherDomain() =
        testApplication {
            val approval =
                PendingApproval(
                    id = "id",
                    clientName = "App",
                    redirectHost = "claude.ai",
                    matchCode = "42",
                    expiresAtMs = Long.MAX_VALUE,
                    otherDomainHost = "chatgpt.com",
                )
            routing { get("/") { call.respondConsentPage(approval, "App", null, 0L) } }
            val html = client.get("/").bodyAsText()
            assertTrue(html.contains("This app identifies itself from chatgpt.com, a different domain."), html)
        }

    @Test
    @DisplayName("escapes a malicious client name")
    fun escapesClientName() {
        val html = consentPageHtml(data(clientName = "<script>alert(1)</script>"))
        assertFalse(html.contains("<script>alert(1)</script>"), "raw script must not appear")
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "name must be HTML-escaped")
    }

    @Test
    @DisplayName("shows the match code and has no approve control")
    fun showsCodeNoApprove() {
        val html = consentPageHtml(data())
        assertTrue(html.contains("42"))
        assertFalse(html.contains("<button"), "page must not contain an approve control")
        assertFalse(html.contains("<form"), "page must not contain a submit form")
    }

    @Test
    @DisplayName("embeds the host and the countdown seconds")
    fun showsHostAndCountdown() {
        val html = consentPageHtml(data())
        assertTrue(html.contains("claude.ai"), "host must be shown")
        assertTrue(html.contains("var remaining = 300"), "countdown must be seeded with the remaining seconds")
    }

    @Test
    @DisplayName("renders the favicon img when an icon url is provided, else only the monogram")
    fun rendersFaviconWhenProvided() {
        val iconUrl = "https://icons.duckduckgo.com/ip3/claude.ai.ico"
        val withIcon = consentPageHtml(data(iconUrl = iconUrl))
        assertTrue(withIcon.contains("""<img class="icon" src="$iconUrl""""))
        assertFalse(consentPageHtml(data()).contains("<img"), "no img element when icon url is null")
    }
}
