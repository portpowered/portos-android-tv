package com.portoperatingsystem.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.portoperatingsystem.tv.R

/**
 * Shows one text notification in a large card over whatever is on screen, then closes itself after the display
 * time or when the viewer presses Back. The window is translucent so video behind it keeps playing. A second
 * notification replaces the first and restarts the timer.
 */
internal class NotificationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { finish() }

    private lateinit var heading: TextView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(dismiss)
        super.onDestroy()
    }

    private fun show(intent: Intent) {
        heading.text = intent.getStringExtra(EXTRA_TITLE)
        message.text = intent.getStringExtra(EXTRA_BODY)
        handler.removeCallbacks(dismiss)
        handler.postDelayed(dismiss, intent.getLongExtra(EXTRA_DISPLAY_MILLIS, DEFAULT_DISPLAY_MILLIS))
    }

    private fun layout(): FrameLayout {
        heading = text(TITLE_SP, bold = true, maxLines = TITLE_LINES)
        message = text(BODY_SP, bold = false, maxLines = BODY_LINES)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(CARD_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(getColor(R.color.background))
                cornerRadius = dp(CARD_RADIUS_DP).toFloat()
            }
            addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(message, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val margin = dp(SCREEN_MARGIN_DP)
            addView(
                card,
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                    setMargins(margin, margin, margin, margin)
                }
            )
        }
    }

    private fun text(sizeSp: Float, bold: Boolean, maxLines: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(R.color.text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(LINE_PADDING_DP), 0, dp(LINE_PADDING_DP))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_DISPLAY_MILLIS = "displayMillis"
        private const val DEFAULT_DISPLAY_MILLIS = 15_000L

        // Sizes are for reading from a sofa: a 10-foot UI uses large type and generous margins.
        private const val TITLE_SP = 48f
        private const val BODY_SP = 32f
        private const val TITLE_LINES = 2
        private const val BODY_LINES = 8
        private const val CARD_PADDING_DP = 48
        private const val CARD_RADIUS_DP = 16
        private const val SCREEN_MARGIN_DP = 96
        private const val LINE_PADDING_DP = 8

        /** The intent that shows [title] and [body] for [displayMillis]. */
        fun intent(context: Context, title: String, body: String, displayMillis: Long): Intent =
            Intent(context, NotificationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_DISPLAY_MILLIS, displayMillis)
    }
}
