package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("StorageLocationProviderImpl")
class StorageLocationProviderTest {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockPackageManager: PackageManager

    @MockK
    private lateinit var mockSettingsRepository: SettingsRepository

    private lateinit var provider: StorageLocationProviderImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(DocumentsContract::class)

        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.packageManager } returns mockPackageManager

        provider = StorageLocationProviderImpl(mockContext, mockSettingsRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
        unmockkStatic(DocumentsContract::class)
    }

    // ─────────────────────────────────────────────────────────────────────
    // getAvailableLocations
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAvailableLocations")
    inner class GetAvailableLocations {

        @Test
        fun `getAvailableLocations returns discovered roots`() = runTest {
            // Arrange
            val treeUriString = "content://com.test.provider/tree/primary%3A"
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns
                mapOf("com.test.provider/primary" to treeUriString)

            val providerInfo = ProviderInfo()
            providerInfo.authority = "com.test.provider"
            providerInfo.packageName = "com.test.provider.pkg"

            val resolveInfo = ResolveInfo()
            resolveInfo.providerInfo = providerInfo

            every {
                mockPackageManager.queryIntentContentProviders(any(), any())
            } returns listOf(resolveInfo)

            val appInfo = ApplicationInfo()
            appInfo.packageName = "com.test.provider.pkg"
            every {
                mockPackageManager.getApplicationInfo("com.test.provider.pkg", 0)
            } returns appInfo
            every {
                mockPackageManager.getApplicationLabel(appInfo)
            } returns "Test Provider" as CharSequence

            val mockRootsUri = mockk<Uri>()
            every { DocumentsContract.buildRootsUri("com.test.provider") } returns mockRootsUri

            val mockCursor = mockk<Cursor>()
            every {
                mockContentResolver.query(eq(mockRootsUri), any(), any(), any(), any())
            } returns mockCursor

            // Column indices: ROOT_ID=0, TITLE=1, DOCUMENT_ID=2, AVAILABLE_BYTES=3, ICON=4
            every { mockCursor.getColumnIndex(any()) } returnsMany listOf(0, 1, 2, 3, 4)
            every { mockCursor.moveToNext() } returnsMany listOf(true, false)
            every { mockCursor.getString(0) } returns "primary"
            every { mockCursor.getString(1) } returns "Internal Storage"
            every { mockCursor.getString(2) } returns "primary:"
            every { mockCursor.isNull(3) } returns false
            every { mockCursor.getLong(3) } returns 5_000_000_000L
            every { mockCursor.isNull(4) } returns true
            every { mockCursor.close() } just Runs

            // Act
            val result = provider.getAvailableLocations()

            // Assert
            assertEquals(1, result.size)
            val location = result[0]
            assertEquals("com.test.provider/primary", location.id)
            assertEquals("Internal Storage", location.name)
            assertEquals("Test Provider", location.providerName)
            assertEquals("com.test.provider", location.authority)
            assertEquals("primary", location.rootId)
            assertEquals("primary:", location.rootDocumentId)
            assertEquals(treeUriString, location.treeUri)
            assertTrue(location.isAuthorized)
            assertEquals(5_000_000_000L, location.availableBytes)
            assertNull(location.iconUri)
            verify { mockCursor.close() }
        }

        @Test
        fun `getAvailableLocations returns empty list when no providers found`() = runTest {
            // Arrange
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns emptyMap()
            every {
                mockPackageManager.queryIntentContentProviders(any(), any())
            } returns emptyList()

            // Act
            val result = provider.getAvailableLocations()

            // Assert
            assertTrue(result.isEmpty())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // authorizeLocation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("authorizeLocation")
    inner class AuthorizeLocation {

        @Test
        fun `authorizeLocation persists tree URI and takes permission`() = runTest {
            // Arrange
            val locationId = "com.test.provider/primary"
            val treeUriString = "content://com.test.provider/tree/primary%3A"
            val mockTreeUri = mockk<Uri>()
            every { mockTreeUri.toString() } returns treeUriString
            every {
                mockContentResolver.takePersistableUriPermission(any(), any())
            } just Runs
            coEvery {
                mockSettingsRepository.addAuthorizedLocation(any(), any())
            } just Runs

            // Act
            provider.authorizeLocation(locationId, mockTreeUri)

            // Assert
            verify {
                mockContentResolver.takePersistableUriPermission(
                    mockTreeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            coVerify {
                mockSettingsRepository.addAuthorizedLocation(locationId, treeUriString)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // deauthorizeLocation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deauthorizeLocation")
    inner class DeauthorizeLocation {

        @Test
        fun `deauthorizeLocation removes entry and releases permission`() = runTest {
            // Arrange
            val locationId = "com.test.provider/primary"
            val treeUriString = "content://com.test.provider/tree/primary%3A"
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns
                mapOf(locationId to treeUriString)
            coEvery { mockSettingsRepository.removeAuthorizedLocation(any()) } just Runs

            val mockParsedUri = mockk<Uri>()
            mockkStatic(Uri::class)
            every { Uri.parse(treeUriString) } returns mockParsedUri
            every {
                mockContentResolver.releasePersistableUriPermission(any(), any())
            } just Runs

            // Act
            provider.deauthorizeLocation(locationId)

            // Assert
            verify {
                mockContentResolver.releasePersistableUriPermission(
                    mockParsedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            coVerify { mockSettingsRepository.removeAuthorizedLocation(locationId) }

            unmockkStatic(Uri::class)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // isLocationAuthorized
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isLocationAuthorized")
    inner class IsLocationAuthorized {

        @Test
        fun `isLocationAuthorized returns true for authorized locations`() = runTest {
            // Arrange
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns
                mapOf("loc1" to "content://tree/loc1")

            // Act
            val result = provider.isLocationAuthorized("loc1")

            // Assert
            assertTrue(result)
        }

        @Test
        fun `isLocationAuthorized returns false for unauthorized locations`() = runTest {
            // Arrange
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns emptyMap()

            // Act
            val result = provider.isLocationAuthorized("loc1")

            // Assert
            assertFalse(result)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getLocationById
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getLocationById")
    inner class GetLocationById {

        @Test
        fun `getLocationById returns null for unknown location`() = runTest {
            // Arrange — set up getAvailableLocations to return an empty list
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns emptyMap()
            every {
                mockPackageManager.queryIntentContentProviders(any(), any())
            } returns emptyList()

            // Act
            val result = provider.getLocationById("nonexistent/location")

            // Assert
            assertNull(result)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getTreeUriForLocation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getTreeUriForLocation")
    inner class GetTreeUriForLocation {

        @Test
        fun `getTreeUriForLocation returns null for unauthorized location`() = runTest {
            // Arrange
            coEvery { mockSettingsRepository.getAuthorizedLocations() } returns emptyMap()

            // Act
            val result = provider.getTreeUriForLocation("unknown/location")

            // Assert
            assertNull(result)
        }
    }
}
