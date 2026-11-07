package com.danielealbano.androidremotecontrolmcp

import android.content.Context
import android.content.ContextWrapper
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuProviderSwitchImpl
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import rikka.shizuku.ShizukuProvider

@DisplayName("McpApplication Shizuku setup")
class McpApplicationShizukuTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(ShizukuProvider::class)
        every { ShizukuProvider.disableAutomaticSuiInitialization() } just runs
        mockkObject(ShizukuProviderSwitchImpl.Companion)
        every { ShizukuProviderSwitchImpl.recordProcessStart(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ShizukuProviderSwitchImpl.Companion)
        unmockkStatic(ShizukuProvider::class)
    }

    private fun attach(base: Context) {
        ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(McpApplication(), base)
    }

    @Test
    fun `attachBaseContext disables Sui auto-initialization`() {
        attach(mockk(relaxed = true))

        verify(exactly = 1) { ShizukuProvider.disableAutomaticSuiInitialization() }
    }

    @Test
    fun `attachBaseContext records the Shizuku provider state of the new process`() {
        val base = mockk<Context>(relaxed = true)

        attach(base)

        verify(exactly = 1) { ShizukuProviderSwitchImpl.recordProcessStart(base) }
    }
}
