package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.RemoteException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

@DisplayName("ShellUserService")
class ShellUserServiceTest {
    private val pm =
        mockk<PackageManager> {
            every { getPackageUid(any<String>(), any<PackageManager.PackageInfoFlags>()) } returns APP_UID
        }
    private val context =
        mockk<Context> {
            every { packageName } returns "com.example"
            every { packageManager } returns pm
        }

    private lateinit var service: ShellUserService

    @BeforeEach
    fun setUp() {
        mockkStatic(Binder::class)
        service = ShellUserService(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Binder::class)
    }

    @Test
    fun `calls from another uid are refused`() {
        every { Binder.getCallingUid() } returns 10_999

        val error = assertThrows<SecurityException> { service.kill(1) }

        assertTrue(error.message.orEmpty().contains("10999"), error.message)
    }

    @Test
    fun `calls from the app uid are accepted`() {
        every { Binder.getCallingUid() } returns APP_UID

        assertDoesNotThrow { service.kill(1) }
    }

    @Test
    fun `deliverExit reports whether the app got the exit`() {
        val callback = mockk<IShellProcessCallback>(relaxed = true)
        val gone = mockk<IShellProcessCallback> { every { onExit(any(), any()) } throws RemoteException() }

        val delivered = service.deliverExit(callback, 4, 0)
        val lost = service.deliverExit(gone, 4, 0)

        assertEquals(true, delivered)
        verify(exactly = 1) { callback.onExit(4, 0) }
        assertEquals(false, lost)
    }

    private companion object {
        const val APP_UID = 10_123
    }
}
