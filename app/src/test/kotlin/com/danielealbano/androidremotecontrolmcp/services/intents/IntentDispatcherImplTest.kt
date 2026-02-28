package com.danielealbano.androidremotecontrolmcp.services.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("IntentDispatcherImpl")
class IntentDispatcherImplTest {
    @MockK
    private lateinit var mockContext: Context

    private lateinit var dispatcher: IntentDispatcherImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(BuildConfig::class)

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } returns mockk()
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk()
        every { anyConstructed<Intent>().setDataAndType(any(), any()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Float>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Double>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } returns mockk()
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<ArrayList<String>>())
        } returns mockk()

        every { mockContext.startActivity(any()) } just Runs
        every { mockContext.sendBroadcast(any()) } just Runs
        every { mockContext.startService(any()) } returns mockk()

        dispatcher = IntentDispatcherImpl(mockContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ─── sendIntent extras tests ─────────────────────────────────────────

    @Nested
    @DisplayName("sendIntent extras")
    inner class SendIntentExtras {
        @Test
        fun `sendIntent with string extra puts String`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("key" to "value"),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("key", "value") }
            }

        @Test
        fun `sendIntent with boolean extra puts Boolean`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("enabled" to true),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("enabled", true) }
            }

        @Test
        fun `sendIntent with small integer extra puts Int`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("count" to 42L),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("count", 42) }
            }

        @Test
        fun `sendIntent with large integer extra puts Long`() =
            runTest {
                val largeValue = 9999999999L
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("bignum" to largeValue),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("bignum", largeValue) }
            }

        @Test
        fun `sendIntent with decimal extra puts Double`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("ratio" to 3.14),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("ratio", 3.14) }
            }

        @Test
        fun `sendIntent with string list extra puts StringArrayList`() =
            runTest {
                val list = listOf("a", "b", "c")
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("items" to list),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify {
                    anyConstructed<Intent>().putExtra("items", ArrayList(list))
                }
            }

        @Test
        fun `sendIntent with null extra value skips extra`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("nullkey" to null),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify(exactly = 0) { anyConstructed<Intent>().putExtra("nullkey", any<String>()) }
            }
    }

    // ─── sendIntent extras_types override tests ──────────────────────────

    @Nested
    @DisplayName("sendIntent extras_types override")
    inner class SendIntentExtrasTypesOverride {
        @Test
        fun `sendIntent with extras_types long override puts Long`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("id" to 42L),
                            extrasTypes = mapOf("id" to "long"),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("id", 42L) }
            }

        @Test
        fun `sendIntent with extras_types float override puts Float`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("temp" to 98.6),
                            extrasTypes = mapOf("temp" to "float"),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("temp", 98.6f) }
            }

        @Test
        fun `sendIntent with extras_types string override converts number to String`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("code" to 42L),
                            extrasTypes = mapOf("code" to "string"),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("code", "42") }
            }

        @Test
        fun `sendIntent with extras_types boolean converts correctly`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("flag" to "true"),
                            extrasTypes = mapOf("flag" to "boolean"),
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().putExtra("flag", true) }
            }

        @Test
        fun `sendIntent with null value and type override returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("key" to null),
                            extrasTypes = mapOf("key" to "string"),
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("is null but type override") == true,
                )
            }

        @Test
        fun `sendIntent with unsupported extras_types value returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("key" to "value"),
                            extrasTypes = mapOf("key" to "unsupported"),
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("Unsupported extras_types") == true,
                )
            }

        @Test
        fun `sendIntent with extras conversion failure returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            extras = mapOf("num" to "abc"),
                            extrasTypes = mapOf("num" to "int"),
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("Failed to convert extra") == true,
                )
            }
    }

    // ─── sendIntent flags tests ──────────────────────────────────────────

    @Nested
    @DisplayName("sendIntent flags")
    inner class SendIntentFlags {
        @Test
        fun `sendIntent with valid flag resolves correctly`() =
            runTest {
                val flagsSlot = slot<Int>()
                every { anyConstructed<Intent>().setFlags(capture(flagsSlot)) } answers { self as Intent }

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "broadcast",
                            action = "android.intent.action.TEST",
                            flags = listOf("FLAG_INCLUDE_STOPPED_PACKAGES"),
                        ),
                    )

                assertTrue(result.isSuccess)
                assertEquals(Intent.FLAG_INCLUDE_STOPPED_PACKAGES, flagsSlot.captured)
            }

        @Test
        fun `sendIntent with invalid flag name returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "broadcast",
                            action = "android.intent.action.TEST",
                            flags = listOf("FLAG_NONEXISTENT"),
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("Unknown flag") == true)
            }

        @Test
        fun `sendIntent with multiple flags combines with bitwise OR`() =
            runTest {
                val flagsSlot = slot<Int>()
                every { anyConstructed<Intent>().setFlags(capture(flagsSlot)) } answers { self as Intent }

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            flags = listOf("FLAG_ACTIVITY_NEW_TASK", "FLAG_ACTIVITY_CLEAR_TOP"),
                        ),
                    )

                assertTrue(result.isSuccess)
                val expected = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                assertEquals(expected, flagsSlot.captured)
            }

        @Test
        fun `sendIntent activity auto-adds FLAG_ACTIVITY_NEW_TASK`() =
            runTest {
                val flagsSlot = slot<Int>()
                every { anyConstructed<Intent>().setFlags(capture(flagsSlot)) } answers { self as Intent }

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isSuccess)
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flagsSlot.captured)
            }
    }

    // ─── sendIntent dispatch mode tests ──────────────────────────────────

    @Nested
    @DisplayName("sendIntent dispatch modes")
    inner class SendIntentDispatchModes {
        @Test
        fun `sendIntent activity calls startActivity`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.startActivity(any()) }
            }

        @Test
        fun `sendIntent broadcast calls sendBroadcast`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "broadcast",
                            action = "android.intent.action.TEST",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.sendBroadcast(any()) }
            }

        @Test
        fun `sendIntent service calls startService`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "service",
                            action = "android.intent.action.TEST",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.startService(any()) }
            }

        @Test
        fun `sendIntent with invalid type returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "invalid",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(result.exceptionOrNull()?.message?.contains("Invalid intent type") == true)
            }
    }

    // ─── sendIntent component tests ──────────────────────────────────────

    @Nested
    @DisplayName("sendIntent component")
    inner class SendIntentComponent {
        @Test
        fun `sendIntent with valid component sets ComponentName`() =
            runTest {
                every { anyConstructed<Intent>().setComponent(any()) } answers { self as Intent }

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            component = "com.example.app/com.example.app.MyActivity",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify(exactly = 1) { anyConstructed<Intent>().setComponent(any()) }
            }

        @Test
        fun `sendIntent with invalid component format returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            component = "invalid-no-slash",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("Invalid component format") == true,
                )
            }

        @Test
        fun `sendIntent with empty package in component returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            component = "/com.example.Activity",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("must not be empty") == true,
                )
            }

        @Test
        fun `sendIntent with empty class in component returns failure`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            component = "com.example.app/",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("must not be empty") == true,
                )
            }
    }

    // ─── sendIntent exception handling tests ─────────────────────────────

    @Nested
    @DisplayName("sendIntent exception handling")
    inner class SendIntentExceptionHandling {
        @Test
        fun `sendIntent wraps ActivityNotFoundException in Result failure`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    ActivityNotFoundException("test detail")

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("No activity found") == true,
                )
            }

        @Test
        fun `sendIntent wraps SecurityException in Result failure with sanitized message`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    SecurityException("Internal android.app.ActivityThread detail")

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertEquals(
                    "Permission denied: not allowed to start component",
                    result.exceptionOrNull()?.message,
                )
            }

        @Test
        fun `sendIntent wraps IllegalStateException in Result failure`() =
            runTest {
                every { mockContext.startService(any()) } throws
                    IllegalStateException("Not allowed to start service")

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "service",
                            action = "android.intent.action.TEST",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("background start restriction") == true,
                )
            }

        @Test
        fun `sendIntent wraps unexpected exception in Result failure with sanitized message`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    RuntimeException("Internal android.app.ActivityThread crash detail")

                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertEquals(
                    "Intent dispatch failed unexpectedly",
                    result.exceptionOrNull()?.message,
                )
            }
    }

    // ─── sendIntent data and type tests ──────────────────────────────────

    @Nested
    @DisplayName("sendIntent data and type")
    inner class SendIntentDataAndType {
        @Test
        fun `sendIntent with data sets intent data`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            data = "https://example.com",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().data = any() }
            }

        @Test
        fun `sendIntent with data only sets data without clearing type`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                            data = "https://example.com",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().data = any() }
                verify(exactly = 0) { anyConstructed<Intent>().setDataAndType(any(), any()) }
            }

        @Test
        fun `sendIntent with type only sets type without clearing data`() =
            runTest {
                val result =
                    dispatcher.sendIntent(
                        SendIntentRequest(
                            type = "activity",
                            action = "android.intent.action.VIEW",
                        ),
                    )

                assertTrue(result.isSuccess)
                verify(exactly = 0) { anyConstructed<Intent>().data = any() }
            }
    }

    // ─── openUri tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("openUri")
    inner class OpenUri {
        @Test
        fun `openUri calls startActivity with ACTION_VIEW`() =
            runTest {
                val result = dispatcher.openUri("https://example.com")

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.startActivity(any()) }
            }

        @Test
        fun `openUri with package_name sets package on intent`() =
            runTest {
                val result =
                    dispatcher.openUri(
                        uri = "https://example.com",
                        packageName = "com.android.chrome",
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().setPackage("com.android.chrome") }
            }

        @Test
        fun `openUri with mime_type uses setDataAndType`() =
            runTest {
                val result =
                    dispatcher.openUri(
                        uri = "content://media/external/images/1",
                        mimeType = "image/jpeg",
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().setDataAndType(any(), "image/jpeg") }
            }

        @Test
        fun `openUri with uri only sets data`() =
            runTest {
                val result = dispatcher.openUri("https://example.com")

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.startActivity(any()) }
            }

        @Test
        fun `openUri wraps ActivityNotFoundException in Result failure`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    ActivityNotFoundException("No handler")

                val result = dispatcher.openUri("custom://unknown")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("No app found to handle URI") == true,
                )
            }

        @Test
        fun `openUri wraps SecurityException in Result failure with sanitized message`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    SecurityException("Internal details here")

                val result = dispatcher.openUri("https://restricted.com")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertEquals(
                    "Permission denied: not allowed to open URI",
                    result.exceptionOrNull()?.message,
                )
            }

        @Test
        fun `openUri wraps unexpected exception in Result failure with sanitized message`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    RuntimeException("Internal crash detail")

                val result = dispatcher.openUri("https://example.com")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertEquals(
                    "Failed to open URI unexpectedly",
                    result.exceptionOrNull()?.message,
                )
            }
    }
}
