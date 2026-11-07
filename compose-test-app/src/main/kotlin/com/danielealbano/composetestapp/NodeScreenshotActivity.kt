package com.danielealbano.composetestapp

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * A long list of views for E2E tests of `get_node_screenshots`:
 *   adb shell am start -n com.danielealbano.composetestapp/.NodeScreenshotActivity
 *
 * A [ScrollView] (content description `shot_list`) holds rows "Shot row 0" .. "Shot row 39" (content description
 * `shot_row_<n>`), a block `tall_two` 1.6 window heights tall after row 10 and a block `tall_long` 4 window heights
 * tall after row 25. Every node is a real view, so the rows below the fold are in the tree as off-screen nodes.
 */
class NodeScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val windowHeight = resources.displayMetrics.heightPixels
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                for (index in 0 until ROW_COUNT) {
                    addView(row(index))
                    if (index == TALL_TWO_AFTER) {
                        addView(block("tall_two", windowHeight * TALL_TWO_SCREENS, TALL_TWO_COLOR))
                    }
                    if (index == TALL_LONG_AFTER) {
                        addView(block("tall_long", windowHeight * TALL_LONG_SCREENS, TALL_LONG_COLOR))
                    }
                }
            }
        setContentView(
            ScrollView(this).apply {
                contentDescription = "shot_list"
                addView(content)
            },
        )
    }

    private fun row(index: Int): TextView =
        TextView(this).apply {
            text = "Shot row $index"
            contentDescription = "shot_row_$index"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP)
            height = dpToPx(ROW_HEIGHT_DP)
        }

    /** A block [blockHeight] px tall of numbered lines on a [color] background, so its parts are told apart. */
    private fun block(
        name: String,
        blockHeight: Float,
        color: Int,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = name
            setBackgroundColor(color)
            minimumHeight = blockHeight.toInt()
            val lines = (blockHeight / dpToPx(BLOCK_LINE_DP)).toInt()
            for (line in 1..lines) {
                addView(
                    TextView(this@NodeScreenshotActivity).apply {
                        text = "$name line $line"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, BLOCK_TEXT_SP)
                        height = dpToPx(BLOCK_LINE_DP)
                    },
                )
            }
        }

    private fun dpToPx(dp: Float): Int {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
        return px.toInt()
    }

    private companion object {
        const val ROW_COUNT = 40
        const val ROW_HEIGHT_DP = 137f
        const val ROW_TEXT_SP = 24f
        const val TALL_TWO_AFTER = 10
        const val TALL_LONG_AFTER = 25
        const val TALL_TWO_SCREENS = 1.6f
        const val TALL_LONG_SCREENS = 4f
        const val BLOCK_LINE_DP = 48f
        const val BLOCK_TEXT_SP = 18f
        val TALL_TWO_COLOR = Color.rgb(255, 236, 179)
        val TALL_LONG_COLOR = Color.rgb(200, 230, 201)
    }
}
