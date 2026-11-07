package com.danielealbano.composetestapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A Compose column for E2E tests of `get_node_screenshots`:
 *   adb shell am start -n com.danielealbano.composetestapp/.NodeScreenshotComposeActivity
 *
 * A [Column] with `verticalScroll` holds rows `compose_row_0` .. `compose_row_3`, then a block `compose_tall` 1.6
 * window heights tall that starts on the first screen, so it is cut at the bottom.
 */
class NodeScreenshotComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val tallHeight = (LocalConfiguration.current.screenHeightDp * TALL_SCREENS).dp
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        for (index in 0 until ROW_COUNT) {
                            Text(
                                text = "Compose row $index",
                                fontSize = ROW_TEXT_SP.sp,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(ROW_HEIGHT_DP.dp)
                                        .semantics { contentDescription = "compose_row_$index" },
                            )
                        }
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(tallHeight)
                                    .background(TALL_COLOR)
                                    .semantics { contentDescription = "compose_tall" },
                        ) {
                            for (line in 1..(tallHeight.value / LINE_HEIGHT_DP).toInt()) {
                                Text(
                                    text = "compose_tall line $line",
                                    fontSize = LINE_TEXT_SP.sp,
                                    modifier = Modifier.height(LINE_HEIGHT_DP.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ROW_COUNT = 4
        const val ROW_HEIGHT_DP = 80
        const val ROW_TEXT_SP = 24
        const val TALL_SCREENS = 1.6f
        const val LINE_HEIGHT_DP = 48
        const val LINE_TEXT_SP = 18
        val TALL_COLOR = Color(0xFFBBDEFB)
    }
}
