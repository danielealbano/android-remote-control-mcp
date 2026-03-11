package com.danielealbano.composetestapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

/**
 * Minimal Compose activity for E2E testing of accessibility tree freshness.
 *
 * Displays a number (default 0) that can be changed via activity intent:
 *   adb shell am start -n com.danielealbano.composetestapp/.MainActivity --ei number 42
 *
 * Uses onNewIntent to receive updates when the activity is already running
 * (launchMode singleTop via FLAG_ACTIVITY_SINGLE_TOP from the caller).
 *
 * The number is displayed with a stable content description "compose_number_display"
 * and the text "Number: <value>" so it can be found via MCP accessibility tools.
 */
class MainActivity : ComponentActivity() {

    private val currentNumber = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate called, intent=$intent, extras=${intent?.extras}")

        handleNumberIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = "Number: ${currentNumber.intValue}",
                            fontSize = 48.sp,
                            modifier = Modifier
                                .testTag("number_display")
                                .semantics {
                                    contentDescription = "compose_number_display"
                                },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent called, intent=$intent, extras=${intent.extras}")
        handleNumberIntent(intent)
    }

    private fun handleNumberIntent(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_NUMBER) == true) {
            val newNumber = intent.getIntExtra(EXTRA_NUMBER, 0)
            Log.i(TAG, "handleNumberIntent: received number=$newNumber, current=${currentNumber.intValue}")
            currentNumber.intValue = newNumber
            Log.i(TAG, "handleNumberIntent: set number to ${currentNumber.intValue}")
        } else {
            Log.i(TAG, "handleNumberIntent: no '$EXTRA_NUMBER' extra in intent")
        }
    }

    companion object {
        private const val TAG = "ComposeTestApp"
        private const val EXTRA_NUMBER = "number"
    }
}
