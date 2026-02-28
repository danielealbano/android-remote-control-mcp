package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationActionData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Notification Tools Integration Tests")
class NotificationToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private fun sampleNotification(
        notificationId: String = "abc123",
        packageName: String = "com.example.app",
        actions: List<NotificationActionData> = emptyList(),
    ): NotificationData =
        NotificationData(
            notificationId = notificationId,
            key = "0|com.example.app|1|null|10001",
            packageName = packageName,
            appName = "Example",
            title = "Test Title",
            text = "Test text",
            bigText = null,
            subText = null,
            timestamp = 1700000000000L,
            isOngoing = false,
            isClearable = true,
            category = null,
            groupKey = null,
            actions = actions,
        )

    @Test
    fun `notification_list returns JSON with notifications array`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            val action =
                NotificationActionData(
                    actionId = "def456",
                    index = 0,
                    title = "Reply",
                    acceptsText = true,
                )
            coEvery {
                deps.notificationProvider.getNotifications(null, null)
            } returns listOf(sampleNotification(actions = listOf(action)))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_list",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("\"notification_id\""))
                assertTrue(text.contains("\"abc123\""))
                assertTrue(text.contains("\"count\":1"))
                assertTrue(text.contains("\"action_id\""))
                assertTrue(text.contains("\"def456\""))
            }
        }

    @Test
    fun `notification_list with package_name filter passes through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.getNotifications("com.example.app", null)
            } returns listOf(sampleNotification())

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_list",
                        arguments = mapOf("package_name" to "com.example.app"),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.notificationProvider.getNotifications("com.example.app", null)
                }
            }
        }

    @Test
    fun `notification_list when not ready returns permission error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_list",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Notification listener not enabled"))
            }
        }

    @Test
    fun `notification_list with limit parameter passes through`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.getNotifications(null, 5)
            } returns listOf(sampleNotification())

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_list",
                        arguments = mapOf("limit" to 5),
                    )
                assertNotEquals(true, result.isError)
                coVerify {
                    deps.notificationProvider.getNotifications(null, 5)
                }
            }
        }

    @Test
    fun `notification_open valid notification_id returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.openNotification("abc123")
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_open",
                        arguments = mapOf("notification_id" to "abc123"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification opened", text)
            }
        }

    @Test
    fun `notification_open unknown id returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.openNotification("unknown")
            } returns Result.failure(IllegalArgumentException("Notification not found: unknown"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_open",
                        arguments = mapOf("notification_id" to "unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Notification not found"))
            }
        }

    @Test
    fun `notification_open when not ready returns permission error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_open",
                        arguments = mapOf("notification_id" to "abc123"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Notification listener not enabled"))
            }
        }

    @Test
    fun `notification_open with no contentIntent returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.openNotification("abc123")
            } returns Result.failure(IllegalStateException("Notification has no content intent"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_open",
                        arguments = mapOf("notification_id" to "abc123"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("no content intent"))
            }
        }

    @Test
    fun `notification_dismiss valid notification_id returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.dismissNotification("abc123")
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_dismiss",
                        arguments = mapOf("notification_id" to "abc123"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification dismissed", text)
            }
        }

    @Test
    fun `notification_dismiss unknown id returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.dismissNotification("unknown")
            } returns Result.failure(IllegalArgumentException("Notification not found: unknown"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_dismiss",
                        arguments = mapOf("notification_id" to "unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Notification not found"))
            }
        }

    @Test
    fun `notification_snooze valid params returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.snoozeNotification("abc123", 60000L)
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_snooze",
                        arguments =
                            mapOf(
                                "notification_id" to "abc123",
                                "duration_ms" to 60000,
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("snoozed"))
            }
        }

    @Test
    fun `notification_snooze missing duration_ms returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_snooze",
                        arguments = mapOf("notification_id" to "abc123"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }

    @Test
    fun `notification_snooze non-positive duration_ms returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_snooze",
                        arguments =
                            mapOf(
                                "notification_id" to "abc123",
                                "duration_ms" to 0,
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("positive"))
            }
        }

    @Test
    fun `notification_action valid action_id returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.executeAction("def456")
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_action",
                        arguments = mapOf("action_id" to "def456"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification action executed", text)
            }
        }

    @Test
    fun `notification_action unknown id returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.executeAction("unknown")
            } returns Result.failure(IllegalArgumentException("Action not found: unknown"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_action",
                        arguments = mapOf("action_id" to "unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Action not found"))
            }
        }

    @Test
    fun `notification_reply valid params returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.replyToAction("def456", "Hello")
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_reply",
                        arguments =
                            mapOf(
                                "action_id" to "def456",
                                "text" to "Hello",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEquals("Reply sent", text)
            }
        }

    @Test
    fun `notification_reply action without text input returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.notificationProvider.isReady() } returns true
            coEvery {
                deps.notificationProvider.replyToAction("def456", "Hello")
            } returns Result.failure(IllegalStateException("Action does not accept text input"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_notification_reply",
                        arguments =
                            mapOf(
                                "action_id" to "def456",
                                "text" to "Hello",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("does not accept text input"))
            }
        }
}
