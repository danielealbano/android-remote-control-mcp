package com.danielealbano.androidremotecontrolmcp.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("E2EConfigReceiver")
class E2EConfigReceiverTest {
    private lateinit var mockContext: Context
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockStorageLocationProvider: StorageLocationProvider
    private lateinit var receiver: E2EConfigReceiver

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockContext = mockk(relaxed = true)
        mockSettingsRepository = mockk(relaxed = true)
        mockStorageLocationProvider = mockk(relaxed = true)

        receiver = E2EConfigReceiver()
        receiver.settingsRepository = mockSettingsRepository
        receiver.storageLocationProvider = mockStorageLocationProvider

        // Bypass Hilt injection by marking the receiver as already injected.
        // The Hilt-generated base class has a volatile boolean `injected` field
        // that gates the inject(context) call in onReceive(). Setting it to true
        // prevents the Hilt injection from running, so our manually-set mocks are used.
        val injectedField = receiver.javaClass.superclass.getDeclaredField("injected")
        injectedField.isAccessible = true
        injectedField.setBoolean(receiver, true)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `ACTION_E2E_CONFIGURE constant is defined`() {
        assertEquals(
            "com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE",
            E2EConfigReceiver.ACTION_E2E_CONFIGURE,
        )
    }

    @Test
    fun `ACTION_E2E_START_SERVER constant is defined`() {
        assertEquals(
            "com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER",
            E2EConfigReceiver.ACTION_E2E_START_SERVER,
        )
    }

    @Test
    fun `handleConfigure updates bearer token`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns "test-token-abc"
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateBearerToken("test-token-abc") } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateBearerToken("test-token-abc") }
        }

    @Test
    fun `handleConfigure updates binding address to NETWORK`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns "0.0.0.0"
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateBindingAddress(BindingAddress.NETWORK) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateBindingAddress(BindingAddress.NETWORK) }
        }

    @Test
    fun `handleConfigure updates port`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns 9090
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updatePort(9090) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updatePort(9090) }
        }

    @Test
    fun `handleConfigure updates auto start on boot`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns true
            every { intent.getBooleanExtra("auto_start_on_boot", false) } returns true
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateAutoStartOnBoot(true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateAutoStartOnBoot(true) }
        }

    @Test
    fun `handleConfigure updates storage location write permission`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns "builtin:downloads"
            every { intent.hasExtra("storage_allow_write") } returns true
            every { intent.getBooleanExtra("storage_allow_write", false) } returns true
            every { intent.hasExtra("storage_allow_delete") } returns false

            coEvery { mockStorageLocationProvider.isLocationAuthorized("builtin:downloads") } returns true
            coEvery { mockStorageLocationProvider.updateLocationAllowWrite("builtin:downloads", true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockStorageLocationProvider.updateLocationAllowWrite("builtin:downloads", true) }
        }

    @Test
    fun `handleConfigure updates storage location delete permission`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns "builtin:downloads"
            every { intent.hasExtra("storage_allow_write") } returns false
            every { intent.hasExtra("storage_allow_delete") } returns true
            every { intent.getBooleanExtra("storage_allow_delete", false) } returns true

            coEvery { mockStorageLocationProvider.isLocationAuthorized("builtin:downloads") } returns true
            coEvery { mockStorageLocationProvider.updateLocationAllowDelete("builtin:downloads", true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockStorageLocationProvider.updateLocationAllowDelete("builtin:downloads", true) }
        }

    @Test
    fun `handleConfigure skips unknown storage location`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns "unknown:location"
            every { intent.hasExtra("storage_allow_write") } returns true
            every { intent.getBooleanExtra("storage_allow_write", false) } returns true

            coEvery { mockStorageLocationProvider.isLocationAuthorized("unknown:location") } returns false

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify(exactly = 0) { mockStorageLocationProvider.updateLocationAllowWrite(any(), any()) }
        }

    companion object {
        private const val COROUTINE_WAIT_MS = 200L
    }
}
