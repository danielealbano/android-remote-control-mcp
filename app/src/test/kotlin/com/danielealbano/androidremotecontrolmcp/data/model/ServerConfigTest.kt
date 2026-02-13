package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ServerConfig")
class ServerConfigTest {
    @Nested
    @DisplayName("default values")
    inner class DefaultValues {
        @Test
        fun `default port is 8080`() {
            val config = ServerConfig()
            assertEquals(8080, config.port)
        }

        @Test
        fun `default binding address is LOCALHOST`() {
            val config = ServerConfig()
            assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
        }

        @Test
        fun `default bearer token is empty`() {
            val config = ServerConfig()
            assertEquals("", config.bearerToken)
        }

        @Test
        fun `default auto start on boot is false`() {
            val config = ServerConfig()
            assertFalse(config.autoStartOnBoot)
        }

        @Test
        fun `default https enabled is false`() {
            val config = ServerConfig()
            assertFalse(config.httpsEnabled)
        }

        @Test
        fun `default certificate source is AUTO_GENERATED`() {
            val config = ServerConfig()
            assertEquals(CertificateSource.AUTO_GENERATED, config.certificateSource)
        }

        @Test
        fun `default certificate hostname is android-mcp local`() {
            val config = ServerConfig()
            assertEquals("android-mcp.local", config.certificateHostname)
        }

        @Test
        fun `default tunnelEnabled is false`() {
            val config = ServerConfig()
            assertFalse(config.tunnelEnabled)
        }

        @Test
        fun `default tunnelProvider is CLOUDFLARE`() {
            val config = ServerConfig()
            assertEquals(TunnelProviderType.CLOUDFLARE, config.tunnelProvider)
        }

        @Test
        fun `default ngrokAuthtoken is empty`() {
            val config = ServerConfig()
            assertEquals("", config.ngrokAuthtoken)
        }

        @Test
        fun `default ngrokDomain is empty`() {
            val config = ServerConfig()
            assertEquals("", config.ngrokDomain)
        }
    }

    @Nested
    @DisplayName("copy behavior")
    inner class CopyBehavior {
        @Test
        fun `copy with changed port preserves other fields`() {
            val original = ServerConfig(port = 8080, bearerToken = "test-token")
            val copied = original.copy(port = 9090)

            assertEquals(9090, copied.port)
            assertEquals("test-token", copied.bearerToken)
            assertEquals(original.bindingAddress, copied.bindingAddress)
        }

        @Test
        fun `copy creates a distinct instance`() {
            val original = ServerConfig()
            val copied = original.copy(port = 9090)

            assertNotEquals(original, copied)
        }
    }

    @Nested
    @DisplayName("companion constants")
    inner class CompanionConstants {
        @Test
        fun `DEFAULT_PORT is 8080`() {
            assertEquals(8080, ServerConfig.DEFAULT_PORT)
        }

        @Test
        fun `MIN_PORT is 1`() {
            assertEquals(1, ServerConfig.MIN_PORT)
        }

        @Test
        fun `MAX_PORT is 65535`() {
            assertEquals(65535, ServerConfig.MAX_PORT)
        }

        @Test
        fun `DEFAULT_CERTIFICATE_HOSTNAME is android-mcp local`() {
            assertEquals("android-mcp.local", ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME)
        }
    }

    @Nested
    @DisplayName("BindingAddress enum")
    inner class BindingAddressTest {
        @Test
        fun `LOCALHOST address is 127_0_0_1`() {
            assertEquals("127.0.0.1", BindingAddress.LOCALHOST.address)
        }

        @Test
        fun `NETWORK address is 0_0_0_0`() {
            assertEquals("0.0.0.0", BindingAddress.NETWORK.address)
        }

        @Test
        fun `fromAddress returns LOCALHOST for known address`() {
            assertEquals(BindingAddress.LOCALHOST, BindingAddress.fromAddress("127.0.0.1"))
        }

        @Test
        fun `fromAddress returns NETWORK for known address`() {
            assertEquals(BindingAddress.NETWORK, BindingAddress.fromAddress("0.0.0.0"))
        }

        @Test
        fun `fromAddress returns LOCALHOST for unknown address`() {
            assertEquals(BindingAddress.LOCALHOST, BindingAddress.fromAddress("192.168.1.1"))
        }
    }

    @Nested
    @DisplayName("CertificateSource enum")
    inner class CertificateSourceTest {
        @Test
        fun `fromName returns AUTO_GENERATED for known name`() {
            assertEquals(CertificateSource.AUTO_GENERATED, CertificateSource.fromName("AUTO_GENERATED"))
        }

        @Test
        fun `fromName returns CUSTOM for known name`() {
            assertEquals(CertificateSource.CUSTOM, CertificateSource.fromName("CUSTOM"))
        }

        @Test
        fun `fromName returns AUTO_GENERATED for unknown name`() {
            assertEquals(CertificateSource.AUTO_GENERATED, CertificateSource.fromName("INVALID"))
        }
    }
}
