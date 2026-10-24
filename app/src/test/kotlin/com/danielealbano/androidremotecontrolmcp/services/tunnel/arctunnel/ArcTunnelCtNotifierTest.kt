package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import io.mockk.EqMatcher
import io.mockk.MockKMatcherScope
import io.mockk.OfTypeMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelCtNotifier")
class ArcTunnelCtNotifierTest {
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<NotificationManagerCompat>(relaxed = true)
    private val notification = mockk<Notification>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class, NotificationManagerCompat::class, PendingIntent::class)
        mockkConstructor(NotificationCompat.Builder::class)
        every { NotificationManagerCompat.from(any()) } returns manager
        every { PendingIntent.getActivity(any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { anyConstructed<NotificationCompat.Builder>().build() } returns notification
        every { context.getString(R.string.notification_arc_tunnel_ct_body, *anyVararg()) } returns "Body"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun permission(granted: Boolean) {
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { ContextCompat.checkSelfPermission(any(), any()) } returns result
    }

    /** The builder created for the alert channel; MockK routes a construction only to matchers known beforehand. */
    private fun MockKMatcherScope.alertChannelBuilder(): NotificationCompat.Builder =
        constructedWith(
            OfTypeMatcher<Context>(Context::class),
            EqMatcher(McpApplication.ARC_TUNNEL_ALERT_CHANNEL_ID),
        )

    @Test
    fun `post notifies 1004 on the alert channel with the fqdn`() {
        permission(granted = true)
        every { alertChannelBuilder().build() } returns notification

        ArcTunnelCtNotifier.post(context, "abc.d.example.test")

        verify { alertChannelBuilder().setContentText("Body") }
        verify { context.getString(R.string.notification_arc_tunnel_ct_body, "abc.d.example.test") }
        verify { manager.notify(ArcTunnelCtNotifier.NOTIFICATION_ID, notification) }
    }

    @Test
    fun `post without POST_NOTIFICATIONS does nothing`() {
        permission(granted = false)

        ArcTunnelCtNotifier.post(context, "abc.d.example.test")

        verify(exactly = 0) { manager.notify(any<Int>(), any()) }
    }

    @Test
    fun `cancel cancels 1004`() {
        ArcTunnelCtNotifier.cancel(context)

        verify { manager.cancel(ArcTunnelCtNotifier.NOTIFICATION_ID) }
    }
}
