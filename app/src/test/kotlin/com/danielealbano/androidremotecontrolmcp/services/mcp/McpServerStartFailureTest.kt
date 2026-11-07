package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpToolCallIndicators
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerStartFailureTest {
    @Test
    fun `failed start closes owned activity listeners before allowing a retry`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            mockkStatic(Log::class)
            every { Log.e(any(), any(), any()) } returns 0
            val previousStatus = McpServerService.serverStatus.value
            val service = McpServerService()
            val listeners = mockk<McpToolCallIndicators>(relaxUnitFun = true)
            service.settingsRepository =
                mockk<SettingsRepository> {
                    coEvery { getServerConfig() } throws IllegalStateException("startup failed")
                }
            service.serverLogRepository = RecordingServerLogRepository()
            // Seed the service-owned resource without requiring an Android service launch.
            val ownedField = McpServerService::class.java.getDeclaredField("toolCallIndicators")
            ownedField.isAccessible = true
            ownedField.set(service, listeners)
            try {
                service.startServer()
                verify(exactly = 1) { listeners.close() }
                assertNull(ownedField.get(service))
                assertTrue(McpServerService.serverStatus.value is ServerStatus.Error)
            } finally {
                val updateStatus =
                    McpServerService::class.java.getDeclaredMethod("updateStatus", ServerStatus::class.java)
                updateStatus.isAccessible = true
                updateStatus.invoke(service, previousStatus)
                unmockkStatic(Log::class)
                Dispatchers.resetMain()
            }
        }
}
