package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.platform.Clipboard
import com.danielealbano.androidremotecontrolmcp.R
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("registerWith")
class RegisterWithTest {
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var clipboard: Clipboard
    private lateinit var toast: Toast
    private val flagValues = mutableListOf<Long>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addCategory(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns mockk()
        toast = mockk(relaxed = true)
        mockkStatic(Toast::class)
        every { Toast.makeText(any<Context>(), any<Int>(), any()) } returns toast
        mockkStatic(PackageManager.ResolveInfoFlags::class)
        every { PackageManager.ResolveInfoFlags.of(any()) } answers {
            flagValues += firstArg<Long>()
            mockk()
        }
        packageManager = mockk()
        browsers("com.android.chrome", default = "com.android.chrome")
        context = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
        every { context.getString(R.string.register_clip_label) } returns "MCP server URL"
        clipboard = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** The installed [installed] browsers, and what a web link resolves to ([default]: a browser or the chooser). */
    private fun browsers(
        vararg installed: String,
        default: String,
    ) {
        every { packageManager.queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) } returns
            installed.map { resolveInfo(it) }
        every { packageManager.resolveActivity(any<Intent>(), any<PackageManager.ResolveInfoFlags>()) } returns
            resolveInfo(default)
    }

    private fun resolveInfo(packageName: String) =
        ResolveInfo().apply { activityInfo = ActivityInfo().apply { this.packageName = packageName } }

    @Test
    @DisplayName("ChatGPT: the URL is copied before its page opens in the default browser, then the steps show")
    fun chatGptCopiesThenOpens() =
        runTest {
            registerWith(context, clipboard, RegisterTarget.CHATGPT, MCP_URL) { error("ChatGPT needs no name") }

            verify { ClipData.newPlainText("MCP server URL", MCP_URL) }
            coVerifyOrder {
                clipboard.setClipEntry(any())
                context.startActivity(any())
            }
            verify { Uri.parse(RegisterLinks.CHATGPT_URL) }
            verify { anyConstructed<Intent>().setPackage("com.android.chrome") }
            verify { Toast.makeText(context, R.string.register_chatgpt_steps, Toast.LENGTH_LONG) }
            verify { toast.show() }
        }

    @Test
    @DisplayName("Claude: nothing is copied and no message shows; its filled-in link opens in the default browser")
    fun claudeOpensFilledInLink() =
        runTest {
            registerWith(context, clipboard, RegisterTarget.CLAUDE, MCP_URL) { "work - Android Remote Control" }

            coVerify(exactly = 0) { clipboard.setClipEntry(any()) }
            verify { Uri.parse(RegisterLinks.claudeUrl("work - Android Remote Control", MCP_URL)) }
            verify { anyConstructed<Intent>().setPackage("com.android.chrome") }
            verify { context.startActivity(any()) }
            verify(exactly = 0) { Toast.makeText(any<Context>(), any<Int>(), any()) }
        }

    @Test
    @DisplayName("the browser is looked up with a scheme-only https link: every browser, then the default one")
    fun browserLookup() =
        runTest {
            registerWith(context, clipboard, RegisterTarget.GEMINI, MCP_URL) { "" }

            verify { Uri.parse("https://") }
            verify { anyConstructed<Intent>().addCategory(Intent.CATEGORY_BROWSABLE) }
            assertEquals(listOf(0L, PackageManager.MATCH_DEFAULT_ONLY.toLong()), flagValues)
        }

    @Test
    @DisplayName("with no default browser (the chooser answers) the link opens where Android picks")
    fun noDefaultBrowser() =
        runTest {
            browsers("com.android.chrome", "org.mozilla.firefox", default = "android")

            registerWith(context, clipboard, RegisterTarget.GEMINI, MCP_URL) { "" }

            verify(exactly = 0) { anyConstructed<Intent>().setPackage(any()) }
            verify { context.startActivity(any()) }
            verify { Toast.makeText(context, R.string.register_gemini_steps, Toast.LENGTH_LONG) }
        }

    @Test
    @DisplayName("when no app can open the link, the message says so")
    fun noAppOpensTheLink() =
        runTest {
            every { context.startActivity(any()) } throws ActivityNotFoundException()

            registerWith(context, clipboard, RegisterTarget.GEMINI, MCP_URL) { "" }

            verify { Toast.makeText(context, R.string.register_no_app, Toast.LENGTH_LONG) }
            verify { toast.show() }
        }

    @Test
    @DisplayName("the phone name is the system device name, else the manufacturer and the model")
    fun phoneNameReadsTheDeviceName() {
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        mockkStatic(Settings.Global::class)
        every { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) } returns "realme Note 70T"
        assertEquals("realme Note 70T", phoneName(context, manufacturer = "realme", model = "RMX5313"))
        every { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) } returns null
        assertEquals("realme RMX5313", phoneName(context, manufacturer = "realme", model = "RMX5313"))
    }

    private companion object {
        const val MCP_URL = "https://abc.d.arc-tunnel.io/mcp"
    }
}
