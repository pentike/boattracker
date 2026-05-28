package hu.fenyveskupa.boattracker

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView

class RuleDetailActivity : Activity() {
    companion object {
        const val EXTRA_IMAGE_RES = "imageRes"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageRes = intent.getIntExtra(EXTRA_IMAGE_RES, 0)
        if (imageRes == 0) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFFFFF9E8.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val image = ImageView(this).apply {
            setImageResource(imageRes)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(0, dp(44), 0, 0)
        })

        val close = Button(this).apply {
            setText(R.string.dismiss)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.dismiss_button_background)
            includeFontPadding = false
            minWidth = dp(40)
            minHeight = dp(40)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))

        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
