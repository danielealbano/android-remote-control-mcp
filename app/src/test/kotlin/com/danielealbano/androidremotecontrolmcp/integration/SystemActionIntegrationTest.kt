package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("System Action Integration Tests")
class SystemActionIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `press_home calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery { deps.actionExecutor.pressHome() } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "press_home")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }

    @Test
    fun `press_back calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery { deps.actionExecutor.pressBack() } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "press_back")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }
}
