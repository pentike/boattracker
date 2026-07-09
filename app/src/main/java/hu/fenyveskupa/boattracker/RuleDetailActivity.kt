package hu.fenyveskupa.boattracker

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RuleDetailActivity : Activity() {
    companion object {
        const val EXTRA_IMAGE_RES = "imageRes"
        const val EXTRA_TITLE_RES = "titleRes"
        const val EXTRA_DESCRIPTION_RES = "descriptionRes"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(TrackerPrefs.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageRes = intent.getIntExtra(EXTRA_IMAGE_RES, 0)
        val titleRes = intent.getIntExtra(EXTRA_TITLE_RES, 0)
        val descriptionRes = intent.getIntExtra(EXTRA_DESCRIPTION_RES, 0)
        if (imageRes == 0 || titleRes == 0 || descriptionRes == 0) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFFFAFBFD.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val rootPaddingTop = root.paddingTop

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, dp(52), 0, 0)
        }
        val scrollPaddingTop = scroll.paddingTop
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        val image = ImageView(this).apply {
            setImageResource(imageRes)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(image, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val title = TextView(this).apply {
            setText(titleRes)
            textSize = 24f
            setTextColor(0xFF002F2A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, dp(18), 0, dp(10))
        }
        content.addView(title)
        val description = TextView(this).apply {
            setText(descriptionRes)
            textSize = 19f
            setTextColor(0xFF001F1C.toInt())
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(description)
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            setMargins(0, 0, 0, 0)
        })

        val close = Button(this).apply {
            setText(R.string.dismiss)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.rule_detail_close_background)
            includeFontPadding = false
            minWidth = dp(40)
            minHeight = dp(40)
            setOnClickListener { finish() }
        }
        val closeParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
        root.addView(close, closeParams)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val statusBarTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            root.setPadding(root.paddingLeft, rootPaddingTop + statusBarTop, root.paddingRight, root.paddingBottom)
            scroll.setPadding(scroll.paddingLeft, scrollPaddingTop + statusBarTop, scroll.paddingRight, scroll.paddingBottom)
            closeParams.topMargin = statusBarTop
            close.layoutParams = closeParams
            insets
        }
        root.systemUiVisibility = root.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        setContentView(root)
        root.post { root.requestApplyInsets() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
