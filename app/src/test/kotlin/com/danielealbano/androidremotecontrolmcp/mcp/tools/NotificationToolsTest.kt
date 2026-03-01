package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationActionData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("NotificationTools")
class NotificationToolsTest {
    private fun createNotificationProvider(): NotificationProvider = mockk()

    private fun sampleNotification(
        notificationId: String = "aabbcc01",
        packageName: String = "com.example.app",
        appName: String = "Example",
        actions: List<NotificationActionData> = emptyList(),
    ): NotificationData =
        NotificationData(
            notificationId = notificationId,
            packageName = packageName,
            appName = appName,
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

    // ─────────────────────────────────────────────────────────────────────
    // NotificationListHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotificationListHandler")
    inner class NotificationListTests {
        @Test
        @DisplayName("when not ready throws PermissionDenied")
        fun whenNotReadyThrowsPermissionDenied() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns false
                val handler = NotificationListHandler(provider)

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null)
                }
            }

        @Test
        @DisplayName("returns JSON with notifications")
        fun returnsJsonWithNotifications() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val action =
                    NotificationActionData(
                        actionId = "11223344",
                        index = 0,
                        title = "Reply",
                        acceptsText = true,
                    )
                coEvery { provider.getNotifications(null, null) } returns
                    listOf(sampleNotification(actions = listOf(action)))
                val handler = NotificationListHandler(provider)

                val result = handler.execute(null)
                val text = (result.content[0] as TextContent).text
                val json = Json.parseToJsonElement(text).jsonObject
                assertEquals(1, json["count"]?.jsonPrimitive?.content?.toInt())
                val notifications = json["notifications"]?.jsonArray
                assertEquals(1, notifications?.size)
                val n = notifications!![0].jsonObject
                assertEquals("aabbcc01", n["notification_id"]?.jsonPrimitive?.content)
                assertEquals("com.example.app", n["package_name"]?.jsonPrimitive?.content)
                val actions = n["actions"]?.jsonArray
                assertEquals(1, actions?.size)
                val a = actions!![0].jsonObject
                assertEquals("11223344", a["action_id"]?.jsonPrimitive?.content)
                assertEquals("Reply", a["title"]?.jsonPrimitive?.content)
                assertEquals("true", a["accepts_text"]?.jsonPrimitive?.content)
            }

        @Test
        @DisplayName("with package_name passes filter")
        fun withPackageNamePassesFilter() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.getNotifications("com.example.app", null) } returns
                    listOf(sampleNotification())
                val handler = NotificationListHandler(provider)

                val args = buildJsonObject { put("package_name", "com.example.app") }
                handler.execute(args)

                coVerify { provider.getNotifications("com.example.app", null) }
            }

        @Test
        @DisplayName("with limit passes limit")
        fun withLimitPassesLimit() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.getNotifications(null, 5) } returns
                    listOf(sampleNotification())
                val handler = NotificationListHandler(provider)

                val args = buildJsonObject { put("limit", 5) }
                handler.execute(args)

                coVerify { provider.getNotifications(null, 5) }
            }

        @Test
        @DisplayName("returns empty JSON when no notifications")
        fun returnsEmptyJsonWhenNoNotifications() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.getNotifications(null, null) } returns emptyList()
                val handler = NotificationListHandler(provider)

                val result = handler.execute(null)
                val text = (result.content[0] as TextContent).text
                val json = Json.parseToJsonElement(text).jsonObject
                assertEquals(0, json["count"]?.jsonPrimitive?.content?.toInt())
                assertEquals(0, json["notifications"]?.jsonArray?.size)
            }

        @Test
        @DisplayName("with limit=0 returns all notifications")
        fun withLimitZeroReturnsAllNotifications() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.getNotifications(null, null) } returns
                    listOf(sampleNotification())
                val handler = NotificationListHandler(provider)

                val args = buildJsonObject { put("limit", 0) }
                handler.execute(args)

                coVerify { provider.getNotifications(null, null) }
            }

        @Test
        @DisplayName("with negative limit returns all notifications")
        fun withNegativeLimitReturnsAllNotifications() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.getNotifications(null, null) } returns
                    listOf(sampleNotification())
                val handler = NotificationListHandler(provider)

                val args = buildJsonObject { put("limit", -1) }
                handler.execute(args)

                coVerify { provider.getNotifications(null, null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NotificationOpenHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotificationOpenHandler")
    inner class NotificationOpenTests {
        @Test
        @DisplayName("when not ready throws PermissionDenied")
        fun whenNotReadyThrowsPermissionDenied() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns false
                val handler = NotificationOpenHandler(provider)

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(buildJsonObject { put("notification_id", "aabbcc01") })
                }
            }

        @Test
        @DisplayName("valid notification_id returns success")
        fun validNotificationIdReturnsSuccess() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.openNotification("aabbcc01") } returns Result.success(Unit)
                val handler = NotificationOpenHandler(provider)

                val result = handler.execute(buildJsonObject { put("notification_id", "aabbcc01") })
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification opened", text)
            }

        @Test
        @DisplayName("missing notification_id throws InvalidParams")
        fun missingNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationOpenHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject {})
                }
            }

        @Test
        @DisplayName("empty notification_id throws InvalidParams")
        fun emptyNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationOpenHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("notification_id", "") })
                }
            }

        @Test
        @DisplayName("unknown notification_id returns error")
        fun unknownNotificationIdReturnsError() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.openNotification("deadbeef") } returns
                    Result.failure(IllegalArgumentException("Notification not found: deadbeef"))
                val handler = NotificationOpenHandler(provider)

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(buildJsonObject { put("notification_id", "deadbeef") })
                }
            }

        @Test
        @DisplayName("invalid hex notification_id throws InvalidParams")
        fun invalidHexNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationOpenHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("notification_id", "GGGGGGGG") })
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NotificationDismissHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotificationDismissHandler")
    inner class NotificationDismissTests {
        @Test
        @DisplayName("valid notification_id returns success")
        fun validNotificationIdReturnsSuccess() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.dismissNotification("aabbcc01") } returns Result.success(Unit)
                val handler = NotificationDismissHandler(provider)

                val result = handler.execute(buildJsonObject { put("notification_id", "aabbcc01") })
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification dismissed", text)
            }

        @Test
        @DisplayName("when not ready throws PermissionDenied")
        fun whenNotReadyThrowsPermissionDenied() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns false
                val handler = NotificationDismissHandler(provider)

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(buildJsonObject { put("notification_id", "aabbcc01") })
                }
            }

        @Test
        @DisplayName("missing notification_id throws InvalidParams")
        fun missingNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationDismissHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject {})
                }
            }

        @Test
        @DisplayName("unknown notification_id returns error")
        fun unknownNotificationIdReturnsError() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.dismissNotification("deadbeef") } returns
                    Result.failure(IllegalArgumentException("Notification not found: deadbeef"))
                val handler = NotificationDismissHandler(provider)

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(buildJsonObject { put("notification_id", "deadbeef") })
                }
            }

        @Test
        @DisplayName("invalid hex notification_id throws InvalidParams")
        fun invalidHexNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationDismissHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("notification_id", "GGGGGGGG") })
                }
            }

        @Test
        @DisplayName("empty notification_id throws InvalidParams")
        fun emptyNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationDismissHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("notification_id", "") })
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NotificationSnoozeHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotificationSnoozeHandler")
    inner class NotificationSnoozeTests {
        @Test
        @DisplayName("valid params returns success")
        fun validParamsReturnsSuccess() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.snoozeNotification("aabbcc01", 60000L) } returns Result.success(Unit)
                val handler = NotificationSnoozeHandler(provider)

                val args =
                    buildJsonObject {
                        put("notification_id", "aabbcc01")
                        put("duration_ms", 60000)
                    }
                val result = handler.execute(args)
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification snoozed for 60000ms", text)
            }

        @Test
        @DisplayName("missing notification_id throws InvalidParams")
        fun missingNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("duration_ms", 60000) })
                }
            }

        @Test
        @DisplayName("empty notification_id throws InvalidParams")
        fun emptyNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "")
                            put("duration_ms", 60000)
                        },
                    )
                }
            }

        @Test
        @DisplayName("missing duration_ms throws InvalidParams")
        fun missingDurationMsThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("notification_id", "aabbcc01") })
                }
            }

        @Test
        @DisplayName("non-positive duration_ms throws InvalidParams")
        fun nonPositiveDurationMsThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "aabbcc01")
                            put("duration_ms", 0)
                        },
                    )
                }
            }

        @Test
        @DisplayName("duration_ms exceeds max throws InvalidParams")
        fun durationMsExceedsMaxThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "aabbcc01")
                            put("duration_ms", 604_800_001)
                        },
                    )
                }
            }

        @Test
        @DisplayName("when not ready throws PermissionDenied")
        fun whenNotReadyThrowsPermissionDenied() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns false
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "aabbcc01")
                            put("duration_ms", 60000)
                        },
                    )
                }
            }

        @Test
        @DisplayName("negative duration_ms throws InvalidParams")
        fun negativeDurationMsThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "aabbcc01")
                            put("duration_ms", -1)
                        },
                    )
                }
            }

        @Test
        @DisplayName("duration_ms at max returns success")
        fun durationMsAtMaxReturnsSuccess() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.snoozeNotification("aabbcc01", 604_800_000L) } returns Result.success(Unit)
                val handler = NotificationSnoozeHandler(provider)

                val result =
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "aabbcc01")
                            put("duration_ms", 604_800_000)
                        },
                    )
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification snoozed for 604800000ms", text)
            }

        @Test
        @DisplayName("invalid hex notification_id throws InvalidParams")
        fun invalidHexNotificationIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationSnoozeHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("notification_id", "GGGGGGGG")
                            put("duration_ms", 60000)
                        },
                    )
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NotificationActionHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotificationActionHandler")
    inner class NotificationActionTests {
        @Test
        @DisplayName("valid action_id returns success")
        fun validActionIdReturnsSuccess() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.executeAction("11223344") } returns Result.success(Unit)
                val handler = NotificationActionHandler(provider)

                val result = handler.execute(buildJsonObject { put("action_id", "11223344") })
                val text = (result.content[0] as TextContent).text
                assertEquals("Notification action executed", text)
            }

        @Test
        @DisplayName("missing action_id throws InvalidParams")
        fun missingActionIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationActionHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject {})
                }
            }

        @Test
        @DisplayName("empty action_id throws InvalidParams")
        fun emptyActionIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationActionHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("action_id", "") })
                }
            }

        @Test
        @DisplayName("when not ready throws PermissionDenied")
        fun whenNotReadyThrowsPermissionDenied() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns false
                val handler = NotificationActionHandler(provider)

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(buildJsonObject { put("action_id", "11223344") })
                }
            }

        @Test
        @DisplayName("unknown action_id returns error")
        fun unknownActionIdReturnsError() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.executeAction("deadbeef") } returns
                    Result.failure(IllegalArgumentException("Action not found: deadbeef"))
                val handler = NotificationActionHandler(provider)

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(buildJsonObject { put("action_id", "deadbeef") })
                }
            }

        @Test
        @DisplayName("invalid hex action_id throws InvalidParams")
        fun invalidHexActionIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationActionHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("action_id", "GGGGGGGG") })
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NotificationReplyHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NotificationReplyHandler")
    inner class NotificationReplyTests {
        @Test
        @DisplayName("valid params returns success")
        fun validParamsReturnsSuccess() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.replyToAction("11223344", "Hello") } returns Result.success(Unit)
                val handler = NotificationReplyHandler(provider)

                val args =
                    buildJsonObject {
                        put("action_id", "11223344")
                        put("text", "Hello")
                    }
                val result = handler.execute(args)
                val text = (result.content[0] as TextContent).text
                assertEquals("Reply sent", text)
            }

        @Test
        @DisplayName("missing action_id throws InvalidParams")
        fun missingActionIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("text", "Hello") })
                }
            }

        @Test
        @DisplayName("empty action_id throws InvalidParams")
        fun emptyActionIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("action_id", "")
                            put("text", "Hello")
                        },
                    )
                }
            }

        @Test
        @DisplayName("missing text throws InvalidParams")
        fun missingTextThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("action_id", "11223344") })
                }
            }

        @Test
        @DisplayName("empty text throws InvalidParams")
        fun emptyTextThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("action_id", "11223344")
                            put("text", "")
                        },
                    )
                }
            }

        @Test
        @DisplayName("action without text input returns error")
        fun actionWithoutTextInputReturnsError() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                coEvery { provider.replyToAction("11223344", "Hello") } returns
                    Result.failure(IllegalStateException("Action does not accept text input"))
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(
                        buildJsonObject {
                            put("action_id", "11223344")
                            put("text", "Hello")
                        },
                    )
                }
            }

        @Test
        @DisplayName("when not ready throws PermissionDenied")
        fun whenNotReadyThrowsPermissionDenied() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns false
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(
                        buildJsonObject {
                            put("action_id", "11223344")
                            put("text", "Hello")
                        },
                    )
                }
            }

        @Test
        @DisplayName("invalid hex action_id throws InvalidParams")
        fun invalidHexActionIdThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("action_id", "GGGGGGGG")
                            put("text", "Hello")
                        },
                    )
                }
            }

        @Test
        @DisplayName("text exceeds max length throws InvalidParams")
        fun textExceedsMaxLengthThrowsInvalidParams() =
            runTest {
                val provider = createNotificationProvider()
                every { provider.isReady() } returns true
                val handler = NotificationReplyHandler(provider)

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("action_id", "11223344")
                            put("text", "a".repeat(10_001))
                        },
                    )
                }
            }
    }
}
