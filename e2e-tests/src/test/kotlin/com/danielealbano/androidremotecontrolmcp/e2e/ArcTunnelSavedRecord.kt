package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * The saved free E2E tunnel identity of the debug app (`files/arc-tunnel/<env>.json`), edited to be what an older app
 * left behind. Only with the MCP server stopped: a running tunnel holds its own copy.
 */
object ArcTunnelSavedRecord {
    private const val DIR = "files/arc-tunnel"
    private const val PUBLIC_CHAIN = "public_chain"
    private const val CERT_CHECK = "cert_check"
    private const val GENERATION = "generation"
    private const val END_CERTIFICATE = "-----END CERTIFICATE-----"

    fun edit(
        baseAddress: String,
        change: (JsonObject) -> JsonObject,
        serial: String = AndroidContainerSetup.adbSerial,
    ) {
        val path = path(baseAddress, serial)
        val edited = change(readFile(path, serial)).toString()
        val encoded = Base64.getEncoder().encodeToString(edited.toByteArray())
        runAs(serial, "sh", "-c", "'echo $encoded | base64 -d > $path'")
    }

    fun read(
        baseAddress: String,
        serial: String = AndroidContainerSetup.adbSerial,
    ): JsonObject = readFile(path(baseAddress, serial), serial)

    /** The marker of the saved certificate; 0 when it has none. */
    fun certCheck(record: JsonObject): Int = record[CERT_CHECK]?.jsonPrimitive?.int ?: 0

    /** How many issuances the saved identity went through. */
    fun generation(record: JsonObject): Int = requireNotNull(record[GENERATION]).jsonPrimitive.int

    fun withoutCertCheck(record: JsonObject): JsonObject = JsonObject(record - CERT_CHECK)

    /** The record with its public chain cut to the leaf, as older Conscrypt saved a Let's Encrypt bundle. */
    fun withLeafOnly(record: JsonObject): JsonObject {
        val chain = requireNotNull(record[PUBLIC_CHAIN]).jsonPrimitive.content
        val leaf = chain.substringBefore(END_CERTIFICATE) + END_CERTIFICATE + "\n"
        return JsonObject(record + (PUBLIC_CHAIN to JsonPrimitive(leaf)))
    }

    private fun readFile(
        path: String,
        serial: String,
    ): JsonObject = Json.parseToJsonElement(runAs(serial, "cat", path)).jsonObject

    private fun path(
        baseAddress: String,
        serial: String,
    ): String = runAs(serial, "sh", "-c", "'grep -l base_address.:.$baseAddress $DIR/*.json'").trim()

    private fun runAs(
        serial: String,
        vararg command: String,
    ): String = AndroidContainerSetup.execAdbOn(serial, "shell", "run-as", AndroidContainerSetup.APP_PACKAGE, *command)
}
