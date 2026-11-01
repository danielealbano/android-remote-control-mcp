package com.danielealbano.composetestapp

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * One empty single-line text field, for E2E tests of the type tools:
 *   adb shell am start --activity-clear-task -n com.danielealbano.composetestapp/.TextFieldActivity --es field compose
 *
 * - `--es field compose` (the default): a Compose [OutlinedTextField] over a String state, like the app's own settings
 *   fields. Its input connection reports no text offset and can lose a DEL key event sent right after a selection.
 * - `--es field view`: a classic Android [EditText].
 *
 * Either is the screen's only `android.widget.EditText` node, so the tests target it with `by=class_name`.
 */
class TextFieldActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewField = intent?.getStringExtra(EXTRA_FIELD) == FIELD_VIEW
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val fieldModifier = Modifier.fillMaxWidth().padding(FIELD_PADDING_DP.dp)
                    if (viewField) {
                        AndroidView(
                            factory = { context ->
                                EditText(context).apply {
                                    inputType = InputType.TYPE_CLASS_TEXT
                                    isSingleLine = true
                                }
                            },
                            modifier = fieldModifier,
                        )
                    } else {
                        var text by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            modifier = fieldModifier,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val EXTRA_FIELD = "field"
        const val FIELD_VIEW = "view"
        const val FIELD_PADDING_DP = 16
    }
}
