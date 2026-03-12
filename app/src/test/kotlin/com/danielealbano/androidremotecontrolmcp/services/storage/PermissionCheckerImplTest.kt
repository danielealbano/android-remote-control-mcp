package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PermissionCheckerImpl")
class PermissionCheckerImplTest {
    private val context = mockk<Context>()
    private val checker = PermissionCheckerImpl(context)

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `hasPermission returns true when granted`() {
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(checker.hasPermission("android.permission.READ_MEDIA_IMAGES"))
    }

    @Test
    fun `hasPermission returns false when denied`() {
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(checker.hasPermission("android.permission.READ_MEDIA_IMAGES"))
    }
}
