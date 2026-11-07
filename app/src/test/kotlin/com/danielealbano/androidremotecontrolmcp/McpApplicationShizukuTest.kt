package com.danielealbano.androidremotecontrolmcp

import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
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
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ShizukuProvider::class)
    }

    @Test
    fun `attachBaseContext disables Sui auto-initialization`() {
        val application = McpApplication()
        val attachBaseContext =
            ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }

        attachBaseContext.invoke(application, mockk<Context>(relaxed = true))

        verify(exactly = 1) { ShizukuProvider.disableAutomaticSuiInitialization() }
    }
}
