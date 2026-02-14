package com.danielealbano.androidremotecontrolmcp.mcp

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

class CertificateManagerTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var certificateManager: CertificateManager

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        context =
            mockk(relaxed = true) {
                every { filesDir } returns tempDir
            }

        certificateManager = CertificateManager(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `getKeyStorePassword generates and persists password`() {
        val password1 = certificateManager.getKeyStorePassword()
        val password2 = certificateManager.getKeyStorePassword()

        assertTrue(password1.isNotEmpty())
        assertEquals(String(password1), String(password2))
    }

    @Test
    fun `getKeyStorePassword generates password of expected length`() {
        val password = certificateManager.getKeyStorePassword()
        // 32 bytes * 2 hex chars per byte = 64 characters
        assertEquals(CertificateManager.PASSWORD_LENGTH * 2, password.size)
    }

    @Test
    fun `password file is created in filesDir`() {
        certificateManager.getKeyStorePassword()
        val passwordFile = File(tempDir, CertificateManager.KEYSTORE_PASSWORD_FILENAME)
        assertTrue(passwordFile.exists())
    }

    // --- Certificate generation tests ---
    // These tests require Bouncy Castle on the test classpath.
    // Ensure bcpkix-jdk18on and bcprov-jdk18on are in testImplementation.

    @Test
    fun `generateSelfSignedCertificate creates keystore file`() {
        certificateManager.generateSelfSignedCertificate("test.local")
        val keystoreFile = File(tempDir, CertificateManager.KEYSTORE_FILENAME)
        assertTrue(keystoreFile.exists())
    }

    @Test
    fun `generateSelfSignedCertificate creates certificate with correct CN`() {
        val keyStore = certificateManager.generateSelfSignedCertificate("test.local")
        val cert = keyStore.getCertificate(CertificateManager.KEY_ALIAS) as X509Certificate
        assertNotNull(cert)
        assertTrue(
            cert.subjectX500Principal.name.contains("CN=test.local"),
            "Certificate CN should contain test.local, got: ${cert.subjectX500Principal.name}",
        )
    }

    @Test
    fun `generateSelfSignedCertificate creates certificate valid for 1 year`() {
        val beforeGeneration = Date()
        val keyStore = certificateManager.generateSelfSignedCertificate("test.local")
        val cert = keyStore.getCertificate(CertificateManager.KEY_ALIAS) as X509Certificate

        // Certificate should not be expired
        cert.checkValidity()

        // Certificate should expire roughly 1 year from now
        val expectedExpiry =
            Calendar
                .getInstance()
                .apply {
                    time = beforeGeneration
                    add(Calendar.YEAR, 1)
                }.time
        // Allow 1 minute tolerance for test execution time
        val toleranceMs = 60_000L
        assertTrue(
            kotlin.math.abs(cert.notAfter.time - expectedExpiry.time) < toleranceMs,
            "Certificate should expire ~1 year from now",
        )
    }

    @Test
    fun `generateSelfSignedCertificate creates RSA 2048-bit key`() {
        val keyStore = certificateManager.generateSelfSignedCertificate("test.local")
        val key = keyStore.getKey(CertificateManager.KEY_ALIAS, certificateManager.getKeyStorePassword())
        assertNotNull(key)
        assertEquals("RSA", key.algorithm)
    }

    // --- Custom certificate import tests ---

    @Test
    fun `importCustomCertificate with wrong password returns failure`() {
        // Create a valid PKCS12 keystore with known password
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, "correct-password".toCharArray())

        val baos = ByteArrayOutputStream()
        keyStore.store(baos, "correct-password".toCharArray())
        val keystoreBytes = baos.toByteArray()

        // Try to import with wrong password
        val result = certificateManager.importCustomCertificate(keystoreBytes, "wrong-password")

        assertTrue(result.isFailure)
    }

    @Test
    fun `importCustomCertificate with valid empty keystore succeeds`() {
        // Create a valid but empty PKCS12 keystore
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, "test-password".toCharArray())

        val baos = ByteArrayOutputStream()
        keyStore.store(baos, "test-password".toCharArray())
        val keystoreBytes = baos.toByteArray()

        val result = certificateManager.importCustomCertificate(keystoreBytes, "test-password")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `importCustomCertificate stores file in filesDir`() {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, "test-password".toCharArray())

        val baos = ByteArrayOutputStream()
        keyStore.store(baos, "test-password".toCharArray())

        certificateManager.importCustomCertificate(baos.toByteArray(), "test-password")

        val customFile = File(tempDir, CertificateManager.CUSTOM_KEYSTORE_FILENAME)
        assertTrue(customFile.exists())
    }
}
