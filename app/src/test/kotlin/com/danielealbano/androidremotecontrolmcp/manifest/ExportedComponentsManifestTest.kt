package com.danielealbano.androidremotecontrolmcp.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the exported-component surface declared in the source manifests.
 *
 * An exported component with no `android:permission` is reachable by every app installed on the
 * device, with no permission of its own. For the ADB-facing configuration surface that is a
 * privilege escalation: a permissionless app could disable authentication, repoint the tunnel at
 * an attacker endpoint and enable boot persistence (GHSA-v82h-m32h-3j39).
 *
 * These tests parse the checked-in manifests directly — no Robolectric, no device — so a component
 * added without a permission fails the build rather than shipping.
 */
@DisplayName("Exported components")
class ExportedComponentsManifestTest {
    @Test
    fun `every exported component in the main manifest is gated or intentionally public`() {
        val offenders =
            componentsIn(MAIN_MANIFEST)
                .filter { it.exported && it.permission == null }
                .map { it.name }
                .filterNot { it in INTENTIONALLY_PUBLIC }

        assertTrue(offenders.isEmpty()) {
            "Exported components in $MAIN_MANIFEST without android:permission: $offenders. " +
                "Either gate the component or, if it must be reachable by any app, add it to " +
                "INTENTIONALLY_PUBLIC with a rationale."
        }
    }

    @Test
    fun `every exported component in the debug manifest is gated`() {
        val offenders =
            componentsIn(DEBUG_MANIFEST)
                .filter { it.exported && it.permission == null }
                .map { it.name }

        assertTrue(offenders.isEmpty()) {
            "Exported components in $DEBUG_MANIFEST without android:permission: $offenders. " +
                "Debug APKs are published as release assets, so the debug source set is not a " +
                "security boundary."
        }
    }

    @Test
    fun `adb-facing components in the main manifest require DUMP`() {
        val byName = componentsIn(MAIN_MANIFEST).associateBy { it.name }

        listOf(
            ".services.mcp.AdbConfigReceiver",
            ".services.mcp.AdbServiceTrampolineActivity",
        ).forEach { name ->
            val component = byName[name] ?: error("$name is not declared in $MAIN_MANIFEST")
            assertEquals(DUMP, component.permission, "$name must be gated on DUMP")
        }
    }

    @Test
    fun `test-only receivers in the debug manifest require DUMP`() {
        val byName = componentsIn(DEBUG_MANIFEST).associateBy { it.name }

        listOf(
            ".debug.E2EConfigReceiver",
            ".debug.OAuthApprovalTestReceiver",
        ).forEach { name ->
            val component = byName[name] ?: error("$name is not declared in $DEBUG_MANIFEST")
            assertEquals(DUMP, component.permission, "$name must be gated on DUMP")
        }
    }

    @Test
    fun `E2EConfigReceiver is confined to the debug manifest`() {
        val declaredInMain = componentsIn(MAIN_MANIFEST).any { it.name == ".debug.E2EConfigReceiver" }

        assertTrue(!declaredInMain) {
            "E2EConfigReceiver must not be declared in $MAIN_MANIFEST — it is test-only and its " +
                "KDoc states it is absent from release builds."
        }
    }

    @Test
    fun `the Shizuku provider ships disabled and gated`() {
        val provider =
            componentsIn(MAIN_MANIFEST).firstOrNull { it.name == SHIZUKU_PROVIDER }
                ?: error("$SHIZUKU_PROVIDER is not declared in $MAIN_MANIFEST")

        assertEquals("false", provider.enabled)
        assertTrue(provider.exported)
        assertEquals(INTERACT_ACROSS_USERS_FULL, provider.permission)
    }

    private data class Component(
        val name: String,
        val exported: Boolean,
        val permission: String?,
        val enabled: String?,
    )

    private fun componentsIn(relativePath: String): List<Component> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder()
                .parse(resolveManifest(relativePath))

        return COMPONENT_TAGS.flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                Component(
                    name = element.getAttribute("android:name"),
                    exported = element.getAttribute("android:exported") == "true",
                    permission = element.getAttribute("android:permission").ifEmpty { null },
                    enabled = element.getAttribute("android:enabled").ifEmpty { null },
                )
            }
        }
    }

    /** Unit tests run with the module directory as CWD; fall back to the repository root. */
    private fun resolveManifest(relativePath: String): File =
        listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.isFile }
            ?: error("Manifest not found: $relativePath (cwd=${File(".").absolutePath})")

    private companion object {
        const val MAIN_MANIFEST = "src/main/AndroidManifest.xml"
        const val DEBUG_MANIFEST = "src/debug/AndroidManifest.xml"
        const val DUMP = "android.permission.DUMP"
        const val SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"
        const val INTERACT_ACROSS_USERS_FULL = "android.permission.INTERACT_ACROSS_USERS_FULL"

        val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

        /**
         * Components that must stay reachable by any caller:
         * - `MainActivity` is the LAUNCHER entry point.
         * - `ShareReceiverActivity` is a share-sheet target, invoked by arbitrary sending apps.
         */
        val INTENTIONALLY_PUBLIC =
            setOf(
                ".ui.MainActivity",
                ".ui.ShareReceiverActivity",
            )
    }
}
