package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import com.jakewharton.processphoenix.ProcessPhoenix
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

@DisplayName("AppRestarterImpl")
class AppRestarterTest {
    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        mockkStatic(ProcessPhoenix::class)
        every { ProcessPhoenix.triggerRebirth(any<Context>()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ProcessPhoenix::class)
    }

    @Test
    fun `restart relaunches the app through ProcessPhoenix`() {
        AppRestarterImpl(context).restart()

        verify(exactly = 1) { ProcessPhoenix.triggerRebirth(context) }
    }
}
