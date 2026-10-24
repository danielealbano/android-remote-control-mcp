package com.danielealbano.composetestapp

import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * A long vertical list in a plain [ScrollView], for E2E tests of scrolling tools:
 *   adb shell am start -n com.danielealbano.composetestapp/.LongListActivity
 *
 * Every row ("Row 0" .. "Row 59", content description `row_<n>`) is a real view, so the
 * rows below the fold are present in the accessibility tree as off-screen nodes and move on screen as the
 * list scrolls, which is what `scroll_to_node` and `scroll_node` act on.
 */
class LongListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rows =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                for (index in 0 until ROW_COUNT) {
                    addView(
                        TextView(this@LongListActivity).apply {
                            text = "Row $index"
                            contentDescription = "row_$index"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP)
                            minHeight = dpToPx(ROW_HEIGHT_DP)
                        },
                    )
                }
            }
        setContentView(
            ScrollView(this).apply {
                contentDescription = "long_list"
                addView(rows)
            },
        )
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    private companion object {
        const val ROW_COUNT = 60
        const val ROW_HEIGHT_DP = 80f
        const val ROW_TEXT_SP = 24f
    }
}
