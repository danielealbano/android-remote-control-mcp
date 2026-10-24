package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.LocationData
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.location.LocationProvider
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Location Tools")
class LocationToolsTest {
    private lateinit var mockLocationProvider: LocationProvider

    @BeforeEach
    fun setUp() {
        mockLocationProvider = mockk(relaxed = true)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("GetLocationHandler")
    inner class GetLocationTests {
        private lateinit var handler: GetLocationHandler

        @BeforeEach
        fun setUp() {
            handler = GetLocationHandler(mockLocationProvider, PrivacyToolTestDoubles.passthroughGate())
        }

        @Test
        fun `get_location with default params calls provider with freshFix false`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(false)
                } returns
                    Result.success(
                        LocationData(37.7749, -122.4194, 10.0f, "123 Main St"),
                    )

                handler.execute(null, freshFixParamEnabled = true)

                coVerify { mockLocationProvider.getLocation(false) }
            }

        @Test
        fun `get_location with fresh_fix true calls provider with freshFix true`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(true)
                } returns
                    Result.success(
                        LocationData(37.7749, -122.4194, 5.0f, null),
                    )

                val params = buildJsonObject { put("fresh_fix", true) }
                handler.execute(params, freshFixParamEnabled = true)

                coVerify { mockLocationProvider.getLocation(true) }
            }

        @Test
        fun `get_location returns JSON with all fields`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(any())
                } returns
                    Result.success(
                        LocationData(37.7749, -122.4194, 10.5f, "123 Main St, SF"),
                    )

                val result = handler.execute(null, freshFixParamEnabled = true)
                val text = (result.content[0] as TextContent).text

                assertTrue(text.contains("\"latitude\""))
                assertTrue(text.contains("37.7749"))
                assertTrue(text.contains("\"longitude\""))
                assertTrue(text.contains("-122.4194"))
                assertTrue(text.contains("\"accuracy_meters\""))
                assertTrue(text.contains("\"street\""))
                assertTrue(text.contains("123 Main St"))
            }

        @Test
        fun `get_location returns JSON with null street`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(any())
                } returns
                    Result.success(
                        LocationData(37.7749, -122.4194, 10.5f, null),
                    )

                val result = handler.execute(null, freshFixParamEnabled = true)
                val text = (result.content[0] as TextContent).text

                assertTrue(text.contains("\"street\":null"))
            }

        @Test
        fun `get_location throws PermissionDenied on SecurityException`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(any())
                } returns
                    Result.failure(
                        SecurityException("Location permission not granted"),
                    )

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null, freshFixParamEnabled = true)
                }
            }

        @Test
        fun `get_location throws ActionFailed on IllegalStateException`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(any())
                } returns
                    Result.failure(
                        IllegalStateException("Google Play Services not available"),
                    )

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(null, freshFixParamEnabled = true)
                }
            }

        @Test
        fun `get_location with fresh_fix non-boolean throws InvalidParams`() =
            runTest {
                val params = buildJsonObject { put("fresh_fix", "yes") }

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params, freshFixParamEnabled = true)
                }
            }

        @Test
        fun `get_location result contains untrusted content warning`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(any())
                } returns
                    Result.success(
                        LocationData(37.7749, -122.4194, 10.5f, null),
                    )

                val result = handler.execute(null, freshFixParamEnabled = true)
                val text = (result.content[0] as TextContent).text

                assertTrue(text.startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            }

        @Test
        fun `get_location with freshFixParamEnabled false forces freshFix false`() =
            runTest {
                coEvery {
                    mockLocationProvider.getLocation(false)
                } returns
                    Result.success(
                        LocationData(37.7749, -122.4194, 10.5f, null),
                    )

                val params = buildJsonObject { put("fresh_fix", true) }
                handler.execute(params, freshFixParamEnabled = false)

                coVerify { mockLocationProvider.getLocation(false) }
            }

        @Test
        fun `register adds tool with correct name and description`() {
            val mockServer = mockk<Server>(relaxed = true)
            val nameSlot = slot<String>()
            val descSlot = slot<String>()

            every {
                mockServer.addTool(
                    name = capture(nameSlot),
                    description = capture(descSlot),
                    inputSchema = any(),
                    handler = any(),
                )
            } returns Unit

            handler.register(
                LoggedToolRegistrar(
                    mockServer,
                    RecordingServerLogRepository(),
                    inFlightToolCalls = InFlightToolCalls(),
                ),
                "android_",
                freshFixParamEnabled = true,
            )

            assertTrue(nameSlot.captured.contains("get_location"))
            assertTrue(descSlot.captured.contains("10 seconds"))
            assertTrue(descSlot.captured.contains("null"))
            assertTrue(descSlot.captured.contains("ACCESS_FINE_LOCATION"))
            assertTrue(descSlot.captured.contains("Google Play Services"))
        }
    }
}
