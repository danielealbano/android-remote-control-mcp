package com.danielealbano.composetestapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Minimal WebView activity for E2E testing of accessibility tree freshness
 * with non-Compose virtual accessibility nodes.
 *
 * Displays a simple HTML page with "Number: 0" that can be updated via intent:
 *   adb shell am start --activity-single-top \
 *     -n com.danielealbano.composetestapp/.WebViewActivity --ei number 42
 *
 * The number is updated via evaluateJavascript, which modifies the DOM without
 * reloading the page. WebView virtual accessibility nodes may return stale data
 * if not refreshed during tree traversal.
 */
class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate called")

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadData(
                """
                <html>
                <body style="display:flex;justify-content:center;align-items:center;height:100vh;margin:0;">
                    <span id="counter" style="font-size:48px;">Number: 0</span>
                </body>
                </html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
            )
        }
        setContentView(webView)

        handleNumberIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent called, extras=${intent.extras}")
        handleNumberIntent(intent)
    }

    private fun handleNumberIntent(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_NUMBER) == true) {
            val newNumber = intent.getIntExtra(EXTRA_NUMBER, 0)
            Log.i(TAG, "handleNumberIntent: updating WebView to number=$newNumber")
            webView?.evaluateJavascript(
                "document.getElementById('counter').textContent = 'Number: $newNumber';",
            ) { result ->
                Log.i(TAG, "handleNumberIntent: evaluateJavascript result=$result")
            }
        } else {
            Log.i(TAG, "handleNumberIntent: no '$EXTRA_NUMBER' extra in intent")
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WebViewTestApp"
        private const val EXTRA_NUMBER = "number"
    }
}
