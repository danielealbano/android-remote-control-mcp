package com.danielealbano.androidremotecontrolmcp.e2e

import java.io.File
import java.security.MessageDigest

object TestSuE2E {
    private const val DEVICE_PATH = "/system/bin/su"

    fun install() {
        val local = File(System.getProperty("e2e.testSu") ?: error("e2e.testSu is not set"))
        val existing = StorageE2E.shell("sha256sum $DEVICE_PATH 2>/dev/null || true").substringBefore(' ').trim()
        check(existing.isEmpty() || existing == sha256(local)) {
            "$DEVICE_PATH exists and is not the test su; refusing to overwrite it"
        }
        AndroidContainerSetup.execAdb("push", local.absolutePath, DEVICE_PATH)
        StorageE2E.shell("chown root:root $DEVICE_PATH && chmod 6755 $DEVICE_PATH")
    }

    fun remove() {
        val local = File(System.getProperty("e2e.testSu") ?: return)
        val existing = StorageE2E.shell("sha256sum $DEVICE_PATH 2>/dev/null || true").substringBefore(' ').trim()
        if (existing == sha256(local)) StorageE2E.shell("rm -f $DEVICE_PATH")
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
