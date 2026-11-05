package com.danielealbano.composetestapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A vote bar like Reddit's, for E2E tests of node accessibility actions:
 *   adb shell am start -n com.danielealbano.composetestapp/.CustomActionsActivity
 *
 * The bar draws two arrows but exposes no node for them: its only semantics are the custom actions "Upvote" and
 * "Downvote", with no text, content description, test tag or click, so an accessibility client lists it only for its
 * actions. Each action changes the counter shown as "Votes: <n>".
 */
class CustomActionsActivity : ComponentActivity() {
    private val votes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "Votes: ${votes.intValue}", fontSize = 32.sp)
                        Row(
                            modifier =
                                Modifier
                                    .padding(top = 24.dp)
                                    .size(width = 240.dp, height = 64.dp)
                                    .background(Color.LightGray)
                                    .clearAndSetSemantics {
                                        customActions =
                                            listOf(
                                                CustomAccessibilityAction("Upvote") {
                                                    votes.intValue++
                                                    true
                                                },
                                                CustomAccessibilityAction("Downvote") {
                                                    votes.intValue--
                                                    true
                                                },
                                            )
                                    },
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "▲", fontSize = 32.sp)
                            Text(text = "▼", fontSize = 32.sp)
                        }
                    }
                }
            }
        }
    }
}
