package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("StorageLocationProviderImpl")
class StorageLocationProviderTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockContentResolver: ContentResolver

    @MockK
    private lateinit var mockSettingsRepository: SettingsRepository

    private lateinit var provider: StorageLocationProviderImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(DocumentsContract::class)
        mockkStatic(DocumentFile::class)

        every { mockContext.contentResolver } returns mockContentResolver

        provider = StorageLocationProviderImpl(mockContext, mockSettingsRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
        unmockkStatic(DocumentsContract::class)
        unmockkStatic(DocumentFile::class)
    }

    // ─────────────────────────────────────────────────────────────────────
    // getAllLocations
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetAllLocations")
    inner class GetAllLocations {
        @Test
        fun `getAllLocations returns stored locations with enriched metadata`() =
            runTest {
                // Arrange
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "My documents",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)

                val mockTreeUri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(treeUriString) } returns mockTreeUri
                every { mockTreeUri.authority } returns "com.test.provider"

                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents"

                val mockRootsUri = mockk<Uri>()
                every { DocumentsContract.buildRootsUri("com.test.provider") } returns mockRootsUri

                val mockCursor = mockk<Cursor>()
                every {
                    mockContentResolver.query(eq(mockRootsUri), any(), any(), any(), any())
                } returns mockCursor

                every {
                    mockCursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                } returns 0
                every {
                    mockCursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                } returns 1
                every { mockCursor.moveToNext() } returnsMany listOf(true, false)
                every { mockCursor.getString(0) } returns "primary"
                every { mockCursor.isNull(1) } returns false
                every { mockCursor.getLong(1) } returns 5_000_000_000L
                every { mockCursor.close() } just Runs

                // Act
                val result = provider.getAllLocations()

                // Assert
                assertEquals(1, result.size)
                val location = result[0]
                assertEquals("com.test.provider/primary:Documents", location.id)
                assertEquals("Documents", location.name)
                assertEquals("/Documents", location.path)
                assertEquals("My documents", location.description)
                assertEquals(treeUriString, location.treeUri)
                assertEquals(5_000_000_000L, location.availableBytes)

                verify { mockCursor.close() }
                unmockkStatic(Uri::class)
            }

        @Test
        fun `getAllLocations returns empty list when no locations stored`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()

                // Act
                val result = provider.getAllLocations()

                // Assert
                assertTrue(result.isEmpty())
            }

        @Test
        fun `getAllLocations returns locations with null availableBytes when queryAvailableBytes fails`() =
            runTest {
                // Arrange
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "My documents",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)

                val mockTreeUri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(treeUriString) } returns mockTreeUri
                every { mockTreeUri.authority } returns "com.test.provider"

                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents"

                val mockRootsUri = mockk<Uri>()
                every { DocumentsContract.buildRootsUri("com.test.provider") } returns mockRootsUri

                every {
                    mockContentResolver.query(eq(mockRootsUri), any(), any(), any(), any())
                } returns null

                // Act
                val result = provider.getAllLocations()

                // Assert
                assertEquals(1, result.size)
                val location = result[0]
                assertEquals("com.test.provider/primary:Documents", location.id)
                assertEquals("Documents", location.name)
                assertNull(location.availableBytes)

                unmockkStatic(Uri::class)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // addLocation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AddLocation")
    inner class AddLocation {
        @Test
        fun `addLocation persists location, takes permission, and returns Unit`() =
            runTest {
                // Arrange
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3ADocuments"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every { DocumentsContract.getTreeDocumentId(mockTreeUri) } returns "primary:Documents"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns "Documents"

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery { mockSettingsRepository.addStoredLocation(any()) } just Runs

                // Act
                provider.addLocation(mockTreeUri, "My documents")

                // Assert
                verify {
                    mockContentResolver.takePersistableUriPermission(
                        mockTreeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                coVerify {
                    mockSettingsRepository.addStoredLocation(
                        match { stored ->
                            stored.id == "com.test.provider/primary:Documents" &&
                                stored.name == "Documents" &&
                                stored.path == "/Documents" &&
                                stored.description == "My documents" &&
                                stored.treeUri == "content://com.test.provider/tree/primary%3ADocuments"
                        },
                    )
                }
            }

        @Test
        fun `addLocation derives path from physical storage document ID`() =
            runTest {
                // Arrange — document ID "primary:Documents/MyProject" → path "/Documents/MyProject"
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3ADocuments%2FMyProject"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents/MyProject"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns "MyProject"

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery { mockSettingsRepository.addStoredLocation(any()) } just Runs

                // Act
                provider.addLocation(mockTreeUri, "Project folder")

                // Assert
                coVerify {
                    mockSettingsRepository.addStoredLocation(
                        match { stored ->
                            stored.path == "/Documents/MyProject"
                        },
                    )
                }
            }

        @Test
        fun `addLocation derives root path for storage root`() =
            runTest {
                // Arrange — document ID "primary:" → path "/"
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3A"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every { DocumentsContract.getTreeDocumentId(mockTreeUri) } returns "primary:"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns "Internal Storage"

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery { mockSettingsRepository.addStoredLocation(any()) } just Runs

                // Act
                provider.addLocation(mockTreeUri, "Root storage")

                // Assert
                coVerify {
                    mockSettingsRepository.addStoredLocation(
                        match { stored ->
                            stored.path == "/"
                        },
                    )
                }
            }

        @Test
        fun `addLocation derives root path for virtual provider with opaque document ID`() =
            runTest {
                // Arrange — document ID with no colon (opaque) → path "/"
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.google.drive.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.google.drive.provider/tree/abc123def456"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "abc123def456"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns "My Drive"

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery { mockSettingsRepository.addStoredLocation(any()) } just Runs

                // Act
                provider.addLocation(mockTreeUri, "Google Drive")

                // Assert
                coVerify {
                    mockSettingsRepository.addStoredLocation(
                        match { stored ->
                            stored.path == "/"
                        },
                    )
                }
            }

        @Test
        fun `addLocation falls back to document ID when DocumentFile getName() returns null`() =
            runTest {
                // Arrange
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3ADocuments"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns null

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery { mockSettingsRepository.addStoredLocation(any()) } just Runs

                // Act
                provider.addLocation(mockTreeUri, "Test location")

                // Assert
                coVerify {
                    mockSettingsRepository.addStoredLocation(
                        match { stored ->
                            stored.name == "primary:Documents"
                        },
                    )
                }
            }

        @Test
        fun `addLocation truncates description to MAX_DESCRIPTION_LENGTH`() =
            runTest {
                // Arrange
                val longDescription = "A".repeat(StorageLocationProvider.MAX_DESCRIPTION_LENGTH + 100)
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3ADocuments"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns "Documents"

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery { mockSettingsRepository.addStoredLocation(any()) } just Runs

                // Act
                provider.addLocation(mockTreeUri, longDescription)

                // Assert
                coVerify {
                    mockSettingsRepository.addStoredLocation(
                        match { stored ->
                            stored.description.length == StorageLocationProvider.MAX_DESCRIPTION_LENGTH
                        },
                    )
                }
            }

        @Test
        fun `addLocation rejects non-content URI scheme`() =
            runTest {
                // Arrange
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "file"

                // Act & Assert
                val exception =
                    assertThrows<IllegalArgumentException> {
                        provider.addLocation(mockTreeUri, "Test")
                    }
                assertTrue(exception.message!!.contains("Invalid URI scheme"))
            }

        @Test
        fun `addLocation rejects non-tree URI`() =
            runTest {
                // Arrange
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns false

                // Act & Assert
                val exception =
                    assertThrows<IllegalArgumentException> {
                        provider.addLocation(mockTreeUri, "Test")
                    }
                assertTrue(exception.message!!.contains("not a valid document tree URI"))
            }

        @Test
        fun `addLocation rejects URI with null authority`() =
            runTest {
                // Arrange
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every { mockTreeUri.authority } returns null

                // Act & Assert
                val exception =
                    assertThrows<IllegalArgumentException> {
                        provider.addLocation(mockTreeUri, "Test")
                    }
                assertTrue(exception.message!!.contains("no authority"))
            }

        @Test
        fun `addLocation rejects duplicate tree URI`() =
            runTest {
                // Arrange
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns treeUriString
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true

                val existingLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "Existing",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(existingLocation)

                // Act & Assert
                val exception =
                    assertThrows<IllegalStateException> {
                        provider.addLocation(mockTreeUri, "Duplicate")
                    }
                assertTrue(exception.message!!.contains("already exists"))
            }

        @Test
        fun `addLocation releases permission if persistence fails after takePersistableUriPermission`() =
            runTest {
                // Arrange
                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.scheme } returns "content"
                every { mockTreeUri.authority } returns "com.test.provider"
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3ADocuments"
                every { DocumentsContract.isTreeUri(mockTreeUri) } returns true
                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents"
                every {
                    mockContentResolver.takePersistableUriPermission(any(), any())
                } just Runs
                every {
                    mockContentResolver.releasePersistableUriPermission(any(), any())
                } just Runs

                val mockDocFile = mockk<DocumentFile>()
                every { DocumentFile.fromTreeUri(mockContext, mockTreeUri) } returns mockDocFile
                every { mockDocFile.name } returns "Documents"

                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()
                coEvery {
                    mockSettingsRepository.addStoredLocation(any())
                } throws RuntimeException("Persistence failed")

                // Act & Assert
                assertThrows<RuntimeException> {
                    provider.addLocation(mockTreeUri, "Test")
                }

                verify {
                    mockContentResolver.releasePersistableUriPermission(
                        mockTreeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // removeLocation
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RemoveLocation")
    inner class RemoveLocation {
        @Test
        fun `removeLocation removes entry and releases permission`() =
            runTest {
                // Arrange
                val locationId = "com.test.provider/primary:Documents"
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = locationId,
                        name = "Documents",
                        path = "/Documents",
                        description = "My documents",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)
                coEvery { mockSettingsRepository.removeStoredLocation(any()) } just Runs

                val mockParsedUri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(treeUriString) } returns mockParsedUri
                every {
                    mockContentResolver.releasePersistableUriPermission(any(), any())
                } just Runs

                // Act
                provider.removeLocation(locationId)

                // Assert
                verify {
                    mockContentResolver.releasePersistableUriPermission(
                        mockParsedUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                coVerify { mockSettingsRepository.removeStoredLocation(locationId) }

                unmockkStatic(Uri::class)
            }

        @Test
        fun `removeLocation handles already-revoked permission gracefully`() =
            runTest {
                // Arrange
                val locationId = "com.test.provider/primary:Documents"
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = locationId,
                        name = "Documents",
                        path = "/Documents",
                        description = "My documents",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)
                coEvery { mockSettingsRepository.removeStoredLocation(any()) } just Runs

                val mockParsedUri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(treeUriString) } returns mockParsedUri
                every {
                    mockContentResolver.releasePersistableUriPermission(any(), any())
                } throws SecurityException("Permission already revoked")

                // Act — should not throw
                provider.removeLocation(locationId)

                // Assert — removeStoredLocation is still called despite permission release failure
                coVerify { mockSettingsRepository.removeStoredLocation(locationId) }

                unmockkStatic(Uri::class)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // updateLocationDescription
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UpdateLocationDescription")
    inner class UpdateLocationDescription {
        @Test
        fun `updateLocationDescription delegates to settings repository`() =
            runTest {
                // Arrange
                val locationId = "com.test.provider/primary:Documents"
                val newDescription = "Updated description"
                coEvery {
                    mockSettingsRepository.updateLocationDescription(any(), any())
                } just Runs

                // Act
                provider.updateLocationDescription(locationId, newDescription)

                // Assert
                coVerify {
                    mockSettingsRepository.updateLocationDescription(locationId, newDescription)
                }
            }

        @Test
        fun `updateLocationDescription with non-existent locationId is a no-op`() =
            runTest {
                // Arrange
                val locationId = "nonexistent/location"
                val description = "Some description"
                coEvery {
                    mockSettingsRepository.updateLocationDescription(any(), any())
                } just Runs

                // Act — should not throw
                provider.updateLocationDescription(locationId, description)

                // Assert — delegates regardless; repository handles the no-op
                coVerify {
                    mockSettingsRepository.updateLocationDescription(locationId, description)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // isLocationAuthorized
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IsLocationAuthorized")
    inner class IsLocationAuthorized {
        @Test
        fun `isLocationAuthorized returns true for existing locations`() =
            runTest {
                // Arrange
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "My docs",
                        treeUri = "content://com.test.provider/tree/primary%3ADocuments",
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)

                // Act
                val result = provider.isLocationAuthorized("com.test.provider/primary:Documents")

                // Assert
                assertTrue(result)
            }

        @Test
        fun `isLocationAuthorized returns false for unknown locations`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()

                // Act
                val result = provider.isLocationAuthorized("unknown/location")

                // Assert
                assertFalse(result)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getLocationById
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetLocationById")
    inner class GetLocationById {
        @Test
        fun `getLocationById returns location for known ID`() =
            runTest {
                // Arrange
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "My docs",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)

                val mockTreeUri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(treeUriString) } returns mockTreeUri
                every { mockTreeUri.authority } returns "com.test.provider"

                every {
                    DocumentsContract.getTreeDocumentId(mockTreeUri)
                } returns "primary:Documents"

                val mockRootsUri = mockk<Uri>()
                every { DocumentsContract.buildRootsUri("com.test.provider") } returns mockRootsUri

                every {
                    mockContentResolver.query(eq(mockRootsUri), any(), any(), any(), any())
                } returns null

                // Act
                val result = provider.getLocationById("com.test.provider/primary:Documents")

                // Assert
                assertNotNull(result)
                assertEquals("com.test.provider/primary:Documents", result!!.id)
                assertEquals("Documents", result.name)
                assertEquals("/Documents", result.path)
                assertEquals("My docs", result.description)
                assertEquals(treeUriString, result.treeUri)
                assertNull(result.availableBytes)

                unmockkStatic(Uri::class)
            }

        @Test
        fun `getLocationById returns null for unknown ID`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()

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
    @DisplayName("GetTreeUriForLocation")
    inner class GetTreeUriForLocation {
        @Test
        fun `getTreeUriForLocation returns URI for known location`() =
            runTest {
                // Arrange
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "My docs",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)

                val mockParsedUri = mockk<Uri>()
                mockkStatic(Uri::class)
                every { Uri.parse(treeUriString) } returns mockParsedUri

                // Act
                val result = provider.getTreeUriForLocation("com.test.provider/primary:Documents")

                // Assert
                assertNotNull(result)
                assertEquals(mockParsedUri, result)

                unmockkStatic(Uri::class)
            }

        @Test
        fun `getTreeUriForLocation returns null for unknown location`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()

                // Act
                val result = provider.getTreeUriForLocation("unknown/location")

                // Assert
                assertNull(result)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // isDuplicateTreeUri
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IsDuplicateTreeUri")
    inner class IsDuplicateTreeUri {
        @Test
        fun `isDuplicateTreeUri returns true for existing tree URI`() =
            runTest {
                // Arrange
                val treeUriString = "content://com.test.provider/tree/primary%3ADocuments"
                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = "com.test.provider/primary:Documents",
                        name = "Documents",
                        path = "/Documents",
                        description = "My docs",
                        treeUri = treeUriString,
                    )
                coEvery { mockSettingsRepository.getStoredLocations() } returns listOf(storedLocation)

                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.toString() } returns treeUriString

                // Act
                val result = provider.isDuplicateTreeUri(mockTreeUri)

                // Assert
                assertTrue(result)
            }

        @Test
        fun `isDuplicateTreeUri returns false for new tree URI`() =
            runTest {
                // Arrange
                coEvery { mockSettingsRepository.getStoredLocations() } returns emptyList()

                val mockTreeUri = mockk<Uri>()
                every { mockTreeUri.toString() } returns
                    "content://com.test.provider/tree/primary%3ANewFolder"

                // Act
                val result = provider.isDuplicateTreeUri(mockTreeUri)

                // Assert
                assertFalse(result)
            }
    }
}
