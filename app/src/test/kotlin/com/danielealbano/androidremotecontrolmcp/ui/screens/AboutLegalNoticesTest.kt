package com.danielealbano.androidremotecontrolmcp.ui.screens

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the GPLv3 "Appropriate Legal Notices" shown by the About screen: the links it opens point to files that
 * exist in the repository, `LICENSE.md` carries the GPL-3.0-or-later grant with the Play services additional
 * permission followed by the verbatim GPLv3 text, and the About strings carry copyright, license and no-warranty.
 * It also guards that the About screen exposes no personal e-mail address: contact goes through the issues link.
 */
@DisplayName("About screen legal notices")
class AboutLegalNoticesTest {
    @Test
    fun `every about link uses https`() {
        val links = allLinks()

        assertTrue(links.isNotEmpty()) { "AboutLinks declares no URL constant" }
        links.forEach { link -> assertTrue(link.startsWith("https://")) { "$link is not an https URL" } }
    }

    @Test
    fun `repository links point to files that exist`() {
        listOf(AboutLinks.LICENSE_URL, AboutLinks.THIRD_PARTY_LICENSES_URL).forEach { url ->
            assertTrue(url.startsWith("${AboutLinks.GITHUB_URL}/blob/main/")) { "$url is not a repository link" }
            assertTrue(repoFile(url.substringAfter("/blob/main/")).isFile) { "$url points to a missing file" }
        }
    }

    @Test
    fun `LICENSE declares GPL 3 or later with the Play services additional permission`() {
        val license = String(lf(repoFile("LICENSE.md").readBytes()), Charsets.UTF_8)

        val expectedTexts =
            listOf(
                "Copyright 2026-2027 Daniele Salvatore Albano",
                "either version 3 of the License, or (at your option) any later",
                "Additional permission under GNU GPL version 3 section 7",
                "com.google.android.gms",
            )
        expectedTexts.forEach { text -> assertTrue(license.contains(text)) { "LICENSE.md lacks \"$text\"" } }
        assertFalse(MIT_WORD.containsMatchIn(license)) { "LICENSE.md still mentions MIT" }
    }

    @Test
    fun `LICENSE ends with the verbatim GPLv3 text`() {
        val license = lf(repoFile("LICENSE.md").readBytes())

        assertTrue(license.size >= GPL_TEXT_SIZE) { "LICENSE.md is only ${license.size} bytes" }
        assertEquals(GPL_TEXT_SHA256, sha256(license.copyOfRange(license.size - GPL_TEXT_SIZE, license.size)))
    }

    @Test
    fun `third-party licenses list names every bundled component family`() {
        val list = repoFile("THIRD_PARTY_LICENSES.md").readText()

        val families = listOf("Google Play services", "cloudflared", "ngrok-java", "| ring |", "DB-IP", "Ai4Privacy")
        families.forEach { name -> assertTrue(list.contains(name)) { "THIRD_PARTY_LICENSES.md lacks \"$name\"" } }
    }

    @Test
    fun `about strings carry the GPL notices`() {
        val strings = stringsXml()
        val footer = strings.getValue("about_footer")

        assertEquals("GNU GPLv3 or later", strings["about_link_license"])
        assertEquals("Third-party licenses", strings["about_link_third_party_licenses"])
        val parts = listOf("Copyright 2026–2027 Daniele Salvatore Albano", "GPLv3 or later", "without warranty")
        parts.forEach { part -> assertTrue(footer.contains(part)) { "about_footer lacks \"$part\": $footer" } }
    }

    @Test
    fun `no about string except the model attribution mentions MIT`() {
        val aboutStrings = stringsXml().filterKeys { it.startsWith("about_") }

        aboutStrings.filterKeys { it != MODEL_ATTRIBUTION }.forEach { (name, value) ->
            assertFalse(MIT_WORD.containsMatchIn(value)) { "$name still mentions MIT: $value" }
        }
        assertTrue(MIT_WORD.containsMatchIn(aboutStrings.getValue(MODEL_ATTRIBUTION))) {
            "$MODEL_ATTRIBUTION must keep naming the model's own MIT license"
        }
    }

    @Test
    fun `about screen exposes no e-mail address`() {
        val aboutStrings = stringsXml().filterKeys { it.startsWith("about_") }
        val aboutScreenSource = sourceFile(ABOUT_SCREEN_KT).readText()

        assertFalse(aboutStrings.containsKey("about_author_email")) { "about_author_email must not exist" }
        aboutStrings.forEach { (name, value) ->
            assertFalse(EMAIL_ADDRESS.containsMatchIn(value)) { "$name contains an e-mail address: $value" }
        }
        assertFalse(aboutScreenSource.contains("mailto:")) { "$ABOUT_SCREEN_KT opens a mailto link" }
        assertFalse(EMAIL_ADDRESS.containsMatchIn(aboutScreenSource)) { "$ABOUT_SCREEN_KT contains an e-mail address" }
    }

    /** Every `const val` String of [AboutLinks], read by reflection so constants added later are covered too. */
    private fun allLinks(): List<String> {
        val fields = AboutLinks::class.java.declaredFields
        return fields
            .filter { field -> Modifier.isStatic(field.modifiers) && Modifier.isPublic(field.modifiers) }
            .filter { field -> field.type == String::class.java }
            .map { field -> field.get(null) as String }
    }

    /** Unit tests run with the module directory as CWD; fall back to the repository root as CWD. */
    private fun repoFile(name: String): File =
        listOf(File("..", name), File(name)).firstOrNull { it.isFile }
            ?: fail("$name not found from ${File(".").absolutePath}")

    /** A file of the `:app` module; unit tests run with the module directory as CWD, else the repository root. */
    private fun sourceFile(relativePath: String): File =
        listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.isFile }
            ?: fail("$relativePath not found from ${File(".").absolutePath}")

    /** The `name` -> text map of `src/main/res/values/strings.xml`. */
    private fun stringsXml(): Map<String, String> {
        val file = sourceFile(STRINGS_XML)
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder()
                .parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { index -> nodes.item(index) as Element }
            .associate { element -> element.getAttribute("name") to element.textContent }
    }

    /** CRLF normalised to LF, so a `core.autocrlf` checkout does not change the hash. */
    private fun lf(bytes: ByteArray): ByteArray {
        val text = String(bytes, Charsets.UTF_8)
        return text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val GPL_TEXT_SIZE = 35149
        const val GPL_TEXT_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
        const val STRINGS_XML = "src/main/res/values/strings.xml"
        const val MODEL_ATTRIBUTION = "about_attribution_privacy_model"
        const val ABOUT_SCREEN_KT =
            "src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/AboutScreen.kt"
        val MIT_WORD = Regex("\\bMIT\\b")
        val EMAIL_ADDRESS = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}
