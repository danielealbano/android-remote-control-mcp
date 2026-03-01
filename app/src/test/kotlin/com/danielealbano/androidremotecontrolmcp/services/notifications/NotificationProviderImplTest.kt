package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("NotificationProviderImplTest")
class NotificationProviderImplTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockPackageManager: PackageManager

    private lateinit var provider: NotificationProviderImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        every { mockContext.packageManager } returns mockPackageManager

        mockkObject(McpNotificationListenerService.Companion)

        provider = NotificationProviderImpl(mockContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Suppress("LongParameterList")
    private fun createMockSbn(
        key: String,
        packageName: String = "com.test.app",
        postTime: Long = 1000L,
        title: String? = "Test Title",
        text: String? = "Test Text",
        bigText: String? = null,
        subText: String? = null,
        isOngoing: Boolean = false,
        isClearable: Boolean = true,
        category: String? = null,
        groupKey: String? = null,
        contentIntent: PendingIntent? = mockk(),
        actions: Array<Notification.Action>? = null,
    ): StatusBarNotification {
        val extras = mockk<Bundle>(relaxed = true)
        every { extras.getCharSequence(Notification.EXTRA_TITLE) } returns title
        every { extras.getCharSequence(Notification.EXTRA_TEXT) } returns text
        every { extras.getCharSequence(Notification.EXTRA_BIG_TEXT) } returns bigText
        every { extras.getCharSequence(Notification.EXTRA_SUB_TEXT) } returns subText

        val flags = if (isOngoing) Notification.FLAG_ONGOING_EVENT else 0
        val notification =
            mockk<Notification>(relaxed = true).apply {
                this.extras = extras
                this.flags = flags
                this.contentIntent = contentIntent
                this.category = category
                this.actions = actions
            }

        val appInfo = mockk<ApplicationInfo>()
        every {
            mockPackageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } returns appInfo
        every { mockPackageManager.getApplicationLabel(appInfo) } returns "Test App"

        return mockk<StatusBarNotification> {
            every { this@mockk.key } returns key
            every { this@mockk.packageName } returns packageName
            every { this@mockk.postTime } returns postTime
            every { this@mockk.notification } returns notification
            every { this@mockk.isClearable } returns isClearable
            every { this@mockk.groupKey } returns groupKey
        }
    }

    private fun createMockAction(
        actionTitle: CharSequence = "",
        pendingIntent: PendingIntent? = mockk(relaxed = true),
        actionRemoteInputs: Array<RemoteInput>? = null,
    ): Notification.Action {
        val action = mockk<Notification.Action>(relaxed = true)
        action.actionIntent = pendingIntent
        action.title = actionTitle
        every { action.remoteInputs } returns actionRemoteInputs
        return action
    }

    private fun setServiceInstance(vararg sbns: StatusBarNotification) {
        val service = mockk<McpNotificationListenerService>(relaxed = true)
        every { service.getNotifications() } returns arrayOf(*sbns)
        every { McpNotificationListenerService.instance } returns service
    }

    @Nested
    @DisplayName("isReady")
    inner class IsReadyTests {
        @Test
        @DisplayName("returns true when service instance is set")
        fun `isReady returns true when service instance is set`() {
            setServiceInstance()
            assertTrue(provider.isReady())
        }

        @Test
        @DisplayName("returns false when service instance is null")
        fun `isReady returns false when service instance is null`() {
            every { McpNotificationListenerService.instance } returns null
            assertFalse(provider.isReady())
        }
    }

    @Nested
    @DisplayName("getNotifications")
    inner class GetNotificationsTests {
        @Test
        @DisplayName("returns all notifications with correct data extraction")
        fun `getNotifications returns all notifications with correct data extraction`() =
            runTest {
                val sbn =
                    createMockSbn(
                        key = "key1",
                        packageName = "com.test.app",
                        postTime = 1000L,
                        title = "Title1",
                        text = "Text1",
                        bigText = "BigText1",
                        subText = "SubText1",
                        isOngoing = true,
                        isClearable = false,
                        category = "msg",
                        groupKey = "group1",
                    )
                setServiceInstance(sbn)

                val result = provider.getNotifications()

                assertEquals(1, result.size)
                val n = result[0]
                assertEquals("com.test.app", n.packageName)
                assertEquals("Test App", n.appName)
                assertEquals("Title1", n.title)
                assertEquals("Text1", n.text)
                assertEquals("BigText1", n.bigText)
                assertEquals("SubText1", n.subText)
                assertEquals(1000L, n.timestamp)
                assertTrue(n.isOngoing)
                assertFalse(n.isClearable)
                assertEquals("msg", n.category)
                assertEquals("group1", n.groupKey)
            }

        @Test
        @DisplayName("with packageName filter returns only matching")
        fun `getNotifications with packageName filter returns only matching`() =
            runTest {
                val sbn1 = createMockSbn(key = "key1", packageName = "com.test.app1", postTime = 1000L)
                val sbn2 = createMockSbn(key = "key2", packageName = "com.test.app2", postTime = 2000L)
                setServiceInstance(sbn1, sbn2)

                val result = provider.getNotifications(packageName = "com.test.app1")

                assertEquals(1, result.size)
                assertEquals("com.test.app1", result[0].packageName)
            }

        @Test
        @DisplayName("with limit returns at most N")
        fun `getNotifications with limit returns at most N`() =
            runTest {
                val sbn1 = createMockSbn(key = "key1", postTime = 1000L)
                val sbn2 = createMockSbn(key = "key2", postTime = 2000L)
                val sbn3 = createMockSbn(key = "key3", postTime = 3000L)
                setServiceInstance(sbn1, sbn2, sbn3)

                val result = provider.getNotifications(limit = 2)

                assertEquals(2, result.size)
            }

        @Test
        @DisplayName("sorted by postTime descending")
        fun `getNotifications sorted by postTime descending`() =
            runTest {
                val sbn1 = createMockSbn(key = "key1", postTime = 1000L)
                val sbn2 = createMockSbn(key = "key2", postTime = 3000L)
                val sbn3 = createMockSbn(key = "key3", postTime = 2000L)
                setServiceInstance(sbn1, sbn2, sbn3)

                val result = provider.getNotifications()

                assertEquals(3000L, result[0].timestamp)
                assertEquals(2000L, result[1].timestamp)
                assertEquals(1000L, result[2].timestamp)
            }

        @Test
        @DisplayName("returns empty list when no notifications")
        fun `getNotifications returns empty list when no notifications`() =
            runTest {
                setServiceInstance()
                val result = provider.getNotifications()
                assertTrue(result.isEmpty())
            }

        @Test
        @DisplayName("when service is null throws IllegalStateException")
        fun `getNotifications when service is null throws IllegalStateException`() {
            every { McpNotificationListenerService.instance } returns null
            assertThrows(IllegalStateException::class.java) {
                runTest {
                    provider.getNotifications()
                }
            }
        }
    }

    @Nested
    @DisplayName("Hash functions")
    inner class HashTests {
        @Test
        @DisplayName("computeNotificationHash produces 8 hex chars")
        fun `computeNotificationHash produces 8 hex chars`() {
            val hash = NotificationProviderImpl.computeNotificationHash("test_key")
            assertEquals(8, hash.length)
            assertTrue(hash.matches(Regex("[0-9a-f]{8}")))
        }

        @Test
        @DisplayName("computeActionHash produces 8 hex chars")
        fun `computeActionHash produces 8 hex chars`() {
            val hash = NotificationProviderImpl.computeActionHash("test_key", 0)
            assertEquals(8, hash.length)
            assertTrue(hash.matches(Regex("[0-9a-f]{8}")))
        }

        @Test
        @DisplayName("computeNotificationHash is deterministic for same key")
        fun `computeNotificationHash is deterministic for same key`() {
            val hash1 = NotificationProviderImpl.computeNotificationHash("key1")
            val hash2 = NotificationProviderImpl.computeNotificationHash("key1")
            assertEquals(hash1, hash2)
        }

        @Test
        @DisplayName("computeActionHash is deterministic for same key and index")
        fun `computeActionHash is deterministic for same key and index`() {
            val hash1 = NotificationProviderImpl.computeActionHash("key1", 0)
            val hash2 = NotificationProviderImpl.computeActionHash("key1", 0)
            assertEquals(hash1, hash2)
        }

        @Test
        @DisplayName("computeNotificationHash differs for different keys")
        fun `computeNotificationHash differs for different keys`() {
            val hash1 = NotificationProviderImpl.computeNotificationHash("key1")
            val hash2 = NotificationProviderImpl.computeNotificationHash("key2")
            assertNotEquals(hash1, hash2)
        }

        @Test
        @DisplayName("computeActionHash differs for different key or index")
        fun `computeActionHash differs for different key or index`() {
            val hash1 = NotificationProviderImpl.computeActionHash("key1", 0)
            val hash2 = NotificationProviderImpl.computeActionHash("key1", 1)
            val hash3 = NotificationProviderImpl.computeActionHash("key2", 0)
            assertNotEquals(hash1, hash2)
            assertNotEquals(hash1, hash3)
        }
    }

    @Nested
    @DisplayName("openNotification")
    inner class OpenNotificationTests {
        @Test
        @DisplayName("finds notification by hash and sends contentIntent")
        fun `openNotification finds notification by hash and sends contentIntent`() =
            runTest {
                val pendingIntent = mockk<PendingIntent>(relaxed = true)
                val sbn = createMockSbn(key = "key1", contentIntent = pendingIntent)
                setServiceInstance(sbn)
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.openNotification(hash)

                assertTrue(result.isSuccess)
                verify { pendingIntent.send() }
            }

        @Test
        @DisplayName("with unknown hash returns failure")
        fun `openNotification with unknown hash returns failure`() =
            runTest {
                setServiceInstance()
                val result = provider.openNotification("abc123")
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("with no contentIntent returns failure")
        fun `openNotification with no contentIntent returns failure`() =
            runTest {
                val sbn = createMockSbn(key = "key1", contentIntent = null)
                setServiceInstance(sbn)
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.openNotification(hash)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
            }

        @Test
        @DisplayName("with CanceledException returns failure")
        fun `openNotification with CanceledException returns failure`() =
            runTest {
                val pendingIntent =
                    mockk<PendingIntent> {
                        every { send() } throws PendingIntent.CanceledException()
                    }
                val sbn = createMockSbn(key = "key1", contentIntent = pendingIntent)
                setServiceInstance(sbn)
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.openNotification(hash)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is PendingIntent.CanceledException)
            }
    }

    @Nested
    @DisplayName("dismissNotification")
    inner class DismissNotificationTests {
        @Test
        @DisplayName("calls cancelNotification with correct key")
        fun `dismissNotification calls cancelNotification with correct key`() =
            runTest {
                val sbn = createMockSbn(key = "key1")
                val service = mockk<McpNotificationListenerService>(relaxed = true)
                every { service.getNotifications() } returns arrayOf(sbn)
                every { McpNotificationListenerService.instance } returns service
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.dismissNotification(hash)

                assertTrue(result.isSuccess)
                verify { service.dismissNotification("key1") }
            }

        @Test
        @DisplayName("with unknown hash returns failure")
        fun `dismissNotification with unknown hash returns failure`() =
            runTest {
                setServiceInstance()
                val result = provider.dismissNotification("abc123")
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("with SecurityException returns failure")
        fun `dismissNotification with SecurityException returns failure`() =
            runTest {
                val sbn = createMockSbn(key = "key1")
                val service = mockk<McpNotificationListenerService>(relaxed = true)
                every { service.getNotifications() } returns arrayOf(sbn)
                every { service.dismissNotification("key1") } throws SecurityException("denied")
                every { McpNotificationListenerService.instance } returns service
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.dismissNotification(hash)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is SecurityException)
            }
    }

    @Nested
    @DisplayName("snoozeNotification")
    inner class SnoozeNotificationTests {
        @Test
        @DisplayName("calls snoozeNotification with correct key and duration")
        fun `snoozeNotification calls snoozeNotification with correct key and duration`() =
            runTest {
                val sbn = createMockSbn(key = "key1")
                val service = mockk<McpNotificationListenerService>(relaxed = true)
                every { service.getNotifications() } returns arrayOf(sbn)
                every { McpNotificationListenerService.instance } returns service
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.snoozeNotification(hash, 60000L)

                assertTrue(result.isSuccess)
                verify { service.snoozeNotificationByKey("key1", 60000L) }
            }

        @Test
        @DisplayName("with unknown hash returns failure")
        fun `snoozeNotification with unknown hash returns failure`() =
            runTest {
                setServiceInstance()
                val result = provider.snoozeNotification("abc123", 60000L)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("with SecurityException returns failure")
        fun `snoozeNotification with SecurityException returns failure`() =
            runTest {
                val sbn = createMockSbn(key = "key1")
                val service = mockk<McpNotificationListenerService>(relaxed = true)
                every { service.getNotifications() } returns arrayOf(sbn)
                every { service.snoozeNotificationByKey("key1", 60000L) } throws SecurityException("denied")
                every { McpNotificationListenerService.instance } returns service
                val hash = NotificationProviderImpl.computeNotificationHash("key1")

                val result = provider.snoozeNotification(hash, 60000L)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is SecurityException)
            }
    }

    @Nested
    @DisplayName("executeAction")
    inner class ExecuteActionTests {
        @Test
        @DisplayName("finds action by hash and sends PendingIntent")
        fun `executeAction finds action by hash and sends PendingIntent`() =
            runTest {
                val actionPendingIntent = mockk<PendingIntent>(relaxed = true)
                val action =
                    createMockAction(
                        actionTitle = "Action1",
                        pendingIntent = actionPendingIntent,
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)
                val actionId = NotificationProviderImpl.computeActionHash("key1", 0)

                val result = provider.executeAction(actionId)

                assertTrue(result.isSuccess)
                verify { actionPendingIntent.send() }
            }

        @Test
        @DisplayName("with unknown hash returns failure")
        fun `executeAction with unknown hash returns failure`() =
            runTest {
                setServiceInstance()
                val result = provider.executeAction("abc123")
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("with null actionIntent returns failure")
        fun `executeAction with null actionIntent returns failure`() =
            runTest {
                val action =
                    createMockAction(
                        actionTitle = "Action1",
                        pendingIntent = null,
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)
                val actionId = NotificationProviderImpl.computeActionHash("key1", 0)

                val result = provider.executeAction(actionId)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
            }

        @Test
        @DisplayName("with CanceledException returns failure")
        fun `executeAction with CanceledException returns failure`() =
            runTest {
                val actionPendingIntent =
                    mockk<PendingIntent> {
                        every { send() } throws PendingIntent.CanceledException()
                    }
                val action =
                    createMockAction(
                        actionTitle = "Action1",
                        pendingIntent = actionPendingIntent,
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)
                val actionId = NotificationProviderImpl.computeActionHash("key1", 0)

                val result = provider.executeAction(actionId)

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is PendingIntent.CanceledException)
            }
    }

    @Nested
    @DisplayName("replyToAction")
    inner class ReplyToActionTests {
        @Test
        @DisplayName("sends PendingIntent with RemoteInput results")
        fun `replyToAction sends PendingIntent with RemoteInput results`() =
            runTest {
                val actionPendingIntent = mockk<PendingIntent>(relaxed = true)
                val remoteInput =
                    mockk<RemoteInput> {
                        every { resultKey } returns "reply_key"
                        every { isDataOnly } returns false
                    }
                val action =
                    createMockAction(
                        actionTitle = "Reply",
                        pendingIntent = actionPendingIntent,
                        actionRemoteInputs = arrayOf(remoteInput),
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)
                val actionId = NotificationProviderImpl.computeActionHash("key1", 0)

                mockkStatic(RemoteInput::class)
                every { RemoteInput.addResultsToIntent(any(), any(), any()) } just Runs

                val result = provider.replyToAction(actionId, "Hello")

                assertTrue(result.isSuccess)
                verify { actionPendingIntent.send(mockContext, 0, any()) }
            }

        @Test
        @DisplayName("with unknown hash returns failure")
        fun `replyToAction with unknown hash returns failure`() =
            runTest {
                setServiceInstance()
                val result = provider.replyToAction("abc123", "Hello")
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }

        @Test
        @DisplayName("with no RemoteInput returns failure")
        fun `replyToAction with no RemoteInput returns failure`() =
            runTest {
                val actionPendingIntent = mockk<PendingIntent>(relaxed = true)
                val action =
                    createMockAction(
                        actionTitle = "Action1",
                        pendingIntent = actionPendingIntent,
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)
                val actionId = NotificationProviderImpl.computeActionHash("key1", 0)

                val result = provider.replyToAction(actionId, "Hello")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
            }

        @Test
        @DisplayName("with CanceledException returns failure")
        fun `replyToAction with CanceledException returns failure`() =
            runTest {
                val actionPendingIntent =
                    mockk<PendingIntent> {
                        every { send(any<Context>(), any(), any<android.content.Intent>()) } throws
                            PendingIntent.CanceledException()
                    }
                val remoteInput =
                    mockk<RemoteInput> {
                        every { resultKey } returns "reply_key"
                        every { isDataOnly } returns false
                    }
                val action =
                    createMockAction(
                        actionTitle = "Reply",
                        pendingIntent = actionPendingIntent,
                        actionRemoteInputs = arrayOf(remoteInput),
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)
                val actionId = NotificationProviderImpl.computeActionHash("key1", 0)

                mockkStatic(RemoteInput::class)
                every { RemoteInput.addResultsToIntent(any(), any(), any()) } just Runs

                val result = provider.replyToAction(actionId, "Hello")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is PendingIntent.CanceledException)
            }
    }

    @Nested
    @DisplayName("toNotificationData")
    inner class ToNotificationDataTests {
        @Test
        @DisplayName("extracts title, text, bigText, subText from extras")
        fun `toNotificationData extracts title, text, bigText, subText from extras`() =
            runTest {
                val sbn =
                    createMockSbn(
                        key = "key1",
                        title = "Title",
                        text = "Text",
                        bigText = "BigText",
                        subText = "SubText",
                    )
                setServiceInstance(sbn)

                val result = provider.getNotifications()

                assertEquals(1, result.size)
                assertEquals("Title", result[0].title)
                assertEquals("Text", result[0].text)
                assertEquals("BigText", result[0].bigText)
                assertEquals("SubText", result[0].subText)
            }

        @Test
        @DisplayName("detects isOngoing from FLAG_ONGOING_EVENT")
        fun `toNotificationData detects isOngoing from FLAG_ONGOING_EVENT`() =
            runTest {
                val sbn = createMockSbn(key = "key1", isOngoing = true)
                setServiceInstance(sbn)

                val result = provider.getNotifications()

                assertTrue(result[0].isOngoing)
            }

        @Test
        @DisplayName("maps actions with acceptsText from RemoteInput")
        fun `toNotificationData maps actions with acceptsText from RemoteInput`() =
            runTest {
                val remoteInput =
                    mockk<RemoteInput> {
                        every { isDataOnly } returns false
                    }
                val action =
                    createMockAction(
                        actionTitle = "Reply",
                        pendingIntent = null,
                        actionRemoteInputs = arrayOf(remoteInput),
                    )
                val sbn = createMockSbn(key = "key1", actions = arrayOf(action))
                setServiceInstance(sbn)

                val result = provider.getNotifications()

                assertEquals(1, result[0].actions.size)
                assertEquals("Reply", result[0].actions[0].title)
                assertTrue(result[0].actions[0].acceptsText)
            }

        @Test
        @DisplayName("uses packageName as appName fallback when getApplicationInfo fails")
        fun `toNotificationData uses packageName as appName fallback when getApplicationInfo fails`() =
            runTest {
                val extras = mockk<Bundle>(relaxed = true)
                every { extras.getCharSequence(any()) } returns null

                val notification =
                    mockk<Notification>(relaxed = true).apply {
                        this.extras = extras
                        this.flags = 0
                        this.contentIntent = null
                        this.category = null
                        this.actions = null
                    }

                every {
                    mockPackageManager.getApplicationInfo(
                        "com.unknown.app",
                        PackageManager.ApplicationInfoFlags.of(0),
                    )
                } throws PackageManager.NameNotFoundException()

                val sbn =
                    mockk<StatusBarNotification> {
                        every { key } returns "key_unknown"
                        every { packageName } returns "com.unknown.app"
                        every { postTime } returns 1000L
                        every { this@mockk.notification } returns notification
                        every { isClearable } returns true
                        every { groupKey } returns null
                    }
                setServiceInstance(sbn)

                val result = provider.getNotifications()

                assertEquals("com.unknown.app", result[0].appName)
            }
    }
}
