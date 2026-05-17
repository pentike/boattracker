package hu.fenyveskupa.boattracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.webkit.WebView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private companion object {
        const val COLOR_BACKGROUND = 0xFFFFF9E8.toInt()
        const val COLOR_TEXT = 0xFF001F1C.toInt()
        const val COLOR_TITLE = 0xFF002F2A.toInt()
        const val COLOR_MUTED = 0xFF4F635E.toInt()
        const val COLOR_MESSAGE = 0xFF003D33.toInt()
        const val COLOR_WARNING = 0xFF4B2A00.toInt()
        const val COLOR_DOT = 0xFF00A878.toInt()
        const val TEXT_TITLE = 24f
        const val TEXT_EVENT = 20f
        const val TEXT_BODY = 19f
        const val TEXT_LABEL = 15f
        const val TEXT_VALUE = 24f
        const val TEXT_MESSAGE = 19f
        const val TEXT_SWITCH = 20f
    }

    private lateinit var domainValue: TextView
    private lateinit var eventValue: TextView
    private lateinit var mottoValue: TextView
    private lateinit var shipValue: TextView
    private lateinit var coordinatesValue: TextView
    private lateinit var lastBroadcastValue: TextView
    private lateinit var messageSection: LinearLayout
    private lateinit var messageValue: TextView
    private lateinit var warningSection: TextView
    private lateinit var messageListTitle: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var sendingDot: View
    private lateinit var broadcastSwitch: Switch
    private lateinit var backgroundLogo: ImageView
    private lateinit var defaultLogo: WebView
    private var suppressSwitchCallback = false
    private var remoteLogoLoadAttempted = false
    private val sendingDotAnimation = AlphaAnimation(1f, 0.25f).apply {
        duration = 650L
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            renderState()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(TrackerPrefs.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        handleIntent(intent)
        renderState()
        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TrackerIntents.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        renderState()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun handleIntent(intent: Intent) {
        val startupUrl = intent.getStringExtra("trackingUrl") ?: intent.dataString
        if (startupUrl.isNullOrBlank()) {
            fetchStartupEvents()
            return
        }
        val config = parseTrackerConfig(startupUrl) ?: return
        initializeBackend(config)
    }

    private fun fetchStartupEvents() {
        thread {
            try {
                val events = fetchStartupEventList()
                if (events.isEmpty()) {
                    throw IllegalStateException("No events returned")
                }
                runOnUiThread { showEventList(events) }
            } catch (_: Exception) {
                runOnUiThread { showStartupErrorPage() }
            }
        }
    }

    private fun fetchStartupEventList(): List<StartupEvent> {
        val connection = URL(TrackerPrefs.STARTUP_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/json")
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return parseStartupEvents(response)
    }

    private fun initializeBackend(config: TrackerConfig) {
        thread {
            try {
                val initializedConfig = fetchInitConfig(config)
                TrackerPrefs.saveConfig(this, initializedConfig)
                if (!remoteLogoLoadAttempted) {
                    remoteLogoLoadAttempted = true
                    loadRemoteLogo(initializedConfig.logoUrl)
                }
                runOnUiThread {
                    renderState()
                    if (hasLocationPermission()) {
                        startTrackerService(initializedConfig.postUrl)
                    }
                }
            } catch (_: Exception) {
                TrackerPrefs.setActiveMessage(this, getString(R.string.communication_error))
                runOnUiThread { renderState() }
            }
        }
    }

    private fun fetchInitConfig(config: TrackerConfig): TrackerConfig {
        val connection = URL(config.initUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/json")
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (response.isBlank()) return config
        return applyInitResponse(config, response)
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (!hasLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 42)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && hasLocationPermission()) {
            TrackerPrefs.trackingUrl(this)?.let { startTrackerService(it) }
        }
    }

    private fun startTrackerService(url: String) {
        val serviceIntent = Intent(this, TrackerService::class.java)
            .setAction(TrackerIntents.ACTION_START)
            .putExtra(TrackerIntents.EXTRA_URL, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopTrackerService() {
        startService(Intent(this, TrackerService::class.java).setAction(TrackerIntents.ACTION_STOP))
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun showStartupErrorPage() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(36), dp(22), dp(36))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        addStartupLogo(content, bottomMargin = 24)

        val title = TextView(this).apply {
            setText(R.string.app_name)
            textSize = TEXT_TITLE
            setTextColor(COLOR_TITLE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
        }
        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val subtitle = TextView(this).apply {
            setText(R.string.startup_error_subtitle)
            textSize = TEXT_EVENT
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
        }
        content.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val text = TextView(this).apply {
            setText(R.string.startup_error_body)
            textSize = TEXT_BODY
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(text)
        setContentView(root)
    }

    private fun showEventList(events: List<StartupEvent>) {
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        addStartupLogo(content, bottomMargin = 16)

        val title = TextView(this).apply {
            setText(R.string.choose_event)
            textSize = TEXT_TITLE
            setTextColor(COLOR_TITLE)
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        events.forEach { event ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.message_list_item_background)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val config = parseTrackerConfig(event.configUrl) ?: return@setOnClickListener
                    buildLayout()
                    renderState()
                    initializeBackend(config)
                }
            }
            val name = TextView(this).apply {
                text = event.name
                textSize = TEXT_BODY
                setTextColor(COLOR_TEXT)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
            val start = TextView(this).apply {
                text = event.start
                textSize = TEXT_LABEL
                setTextColor(COLOR_MUTED)
                includeFontPadding = false
                setPadding(0, dp(6), 0, 0)
            }
            item.addView(name)
            item.addView(start)
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        setContentView(root)
    }

    private fun addStartupLogo(parent: LinearLayout, bottomMargin: Int) {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        parent.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            this.bottomMargin = dp(bottomMargin)
        })
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        defaultLogo = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.18f
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            loadUrl("file:///android_asset/fenyvesvit_logo.svg")
        }
        root.addView(defaultLogo, FrameLayout.LayoutParams(-1, -1))

        backgroundLogo = ImageView(this).apply {
            visibility = View.GONE
            alpha = 0.18f
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(backgroundLogo, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply {
            setText(R.string.app_name)
            textSize = TEXT_TITLE
            setTextColor(COLOR_TITLE)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
        }
        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        eventValue = TextView(this).apply {
            textSize = TEXT_EVENT
            setTextColor(COLOR_TITLE)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            visibility = View.GONE
        }
        content.addView(eventValue, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        mottoValue = TextView(this).apply {
            textSize = TEXT_BODY
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            visibility = View.GONE
        }
        content.addView(mottoValue, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        messageSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = getDrawable(R.drawable.message_background)
            visibility = View.GONE
        }
        messageValue = TextView(this).apply {
            textSize = TEXT_MESSAGE
            setTextColor(COLOR_MESSAGE)
            includeFontPadding = false
        }
        val dismiss = Button(this).apply {
            setText(R.string.dismiss)
            textSize = TEXT_BODY
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.dismiss_button_background)
            includeFontPadding = false
            minWidth = dp(40)
            minHeight = dp(40)
            setOnClickListener {
                TrackerPrefs.clearMessage(this@MainActivity)
                renderState()
            }
        }
        val messageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        messageRow.addView(messageValue, LinearLayout.LayoutParams(0, -2, 1f))
        messageRow.addView(dismiss, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            leftMargin = dp(8)
        })
        messageSection.addView(messageRow)
        content.addView(messageSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        warningSection = TextView(this).apply {
            setText(R.string.broadcast_off_warning)
            textSize = TEXT_MESSAGE
            setTextColor(COLOR_WARNING)
            background = getDrawable(R.drawable.warning_background)
            includeFontPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        content.addView(warningSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        shipValue = TextView(this).apply {
            setText(R.string.unknown_value)
            textSize = TEXT_VALUE
            setTextColor(COLOR_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }
        content.addView(shipValue)

        val positionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        coordinatesValue = addIconValue(positionRow, R.drawable.ic_location, weight = 1f)
        lastBroadcastValue = addIconValue(positionRow, R.drawable.ic_clock)
        content.addView(positionRow)

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val switchLabel = TextView(this).apply {
            setText(R.string.broadcast_position)
            textSize = TEXT_SWITCH
            setTextColor(COLOR_TITLE)
            includeFontPadding = false
        }
        broadcastSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                if (suppressSwitchCallback) return@setOnCheckedChangeListener
                TrackerPrefs.setEnabled(this@MainActivity, isChecked)
                if (isChecked) {
                    TrackerPrefs.trackingUrl(this@MainActivity)?.let { startTrackerService(it) }
                } else {
                    stopTrackerService()
                }
                renderState()
            }
        }
        switchRow.addView(switchLabel, LinearLayout.LayoutParams(0, -2, 1f))
        switchRow.addView(broadcastSwitch)
        content.addView(switchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        addLanguageSelector(content)
        domainValue = addInfoRow(content, R.string.broadcast_domain, showSendingDot = true)

        messageListTitle = TextView(this).apply {
            setText(R.string.messages)
            textSize = TEXT_LABEL
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            setPadding(0, dp(18), 0, dp(8))
            visibility = View.GONE
        }
        content.addView(messageListTitle)

        messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(messageList)

        setContentView(root)
    }

    private fun addInfoRow(
        parent: LinearLayout,
        labelRes: Int,
        showSendingDot: Boolean = false,
    ): TextView {
        val labelView = TextView(this).apply {
            setText(labelRes)
            textSize = TEXT_LABEL
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
        }
        if (showSendingDot) {
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            sendingDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_DOT)
                }
            }
            labelRow.addView(sendingDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                rightMargin = dp(8)
            })
            labelRow.addView(labelView)
            parent.addView(labelRow)
        } else {
            parent.addView(labelView)
        }
        val valueView = TextView(this).apply {
            setText(R.string.unknown_value)
            textSize = TEXT_VALUE
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            setPadding(0, dp(4), 0, dp(14))
        }
        parent.addView(valueView)
        return valueView
    }

    private fun addLanguageSelector(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val label = TextView(this).apply {
            setText(R.string.language)
            textSize = TEXT_LABEL
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(languageButton(), LinearLayout.LayoutParams(-2, dp(40)))
        parent.addView(row)
    }

    private fun languageButton(): Button {
        return Button(this).apply {
            text = currentLanguageLabel()
            textSize = TEXT_LABEL
            setTextColor(COLOR_TITLE)
            includeFontPadding = false
            isAllCaps = false
            setOnClickListener { showLanguageDialog() }
        }
    }

    private fun currentLanguageLabel(): String {
        return when (TrackerPrefs.language(this)) {
            "hu" -> getString(R.string.language_hungarian)
            else -> getString(R.string.language_english)
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.language_english),
            getString(R.string.language_hungarian),
        )
        val languageCodes = arrayOf("en", "hu")
        val checked = languageCodes.indexOf(TrackerPrefs.language(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(languages, checked) { dialog, which ->
                TrackerPrefs.setLanguage(this, languageCodes[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun addIconValue(parent: LinearLayout, iconRes: Int, weight: Float = 0f): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            alpha = 0.95f
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            rightMargin = dp(6)
        })
        val value = TextView(this).apply {
            setText(R.string.unknown_value)
            textSize = TEXT_BODY
            setTextColor(COLOR_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        row.addView(value)
        val params = if (weight > 0f) {
            LinearLayout.LayoutParams(0, -2, weight)
        } else {
            LinearLayout.LayoutParams(-2, -2)
        }
        parent.addView(row, params)
        return value
    }

    private fun renderState() {
        domainValue.text = TrackerPrefs.domain(this)
        val event = TrackerPrefs.event(this)
        eventValue.text = event
        eventValue.visibility = if (event.isBlank()) View.GONE else View.VISIBLE
        val motto = TrackerPrefs.motto(this)
        mottoValue.text = motto
        mottoValue.visibility = if (motto.isBlank()) View.GONE else View.VISIBLE
        shipValue.text = TrackerPrefs.ship(this)
        coordinatesValue.text = getString(
            R.string.coordinates_degrees_format,
            TrackerPrefs.latitude(this),
            TrackerPrefs.longitude(this),
        )
        val last = TrackerPrefs.lastBroadcast(this)
        lastBroadcastValue.text = if (last == 0L) {
            getString(R.string.unknown_value)
        } else {
            DateFormat.getTimeFormat(this).format(Date(last))
        }
        val msg = TrackerPrefs.message(this)
        messageSection.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
        messageValue.text = msg
        renderMessageList()
        val enabled = TrackerPrefs.enabled(this)
        warningSection.visibility = if (enabled) View.GONE else View.VISIBLE
        val communicationOk = msg != getString(R.string.communication_error)
        if (enabled && communicationOk) {
            sendingDot.visibility = View.VISIBLE
            if (sendingDot.animation == null) {
                sendingDot.startAnimation(sendingDotAnimation)
            }
        } else {
            sendingDot.clearAnimation()
            sendingDot.visibility = View.GONE
        }
        suppressSwitchCallback = true
        broadcastSwitch.isChecked = enabled
        suppressSwitchCallback = false
    }

    private fun loadRemoteLogo(logoUrl: String) {
        if (logoUrl.isBlank()) return
        thread {
            try {
                val connection = URL(logoUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.inputStream.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        runOnUiThread {
                            defaultLogo.visibility = View.GONE
                            backgroundLogo.setImageBitmap(bitmap)
                            backgroundLogo.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
                // The built-in logo remains visible when the remote logo is unavailable.
            }
        }
    }

    private fun renderMessageList() {
        val messages = TrackerPrefs.messages(this)
        messageList.removeAllViews()
        messageListTitle.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
        messageList.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
        messages.forEach { message ->
            val item = TextView(this).apply {
                text = message
                textSize = TEXT_BODY
                setTextColor(COLOR_TEXT)
                includeFontPadding = false
                background = getDrawable(R.drawable.message_list_item_background)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            messageList.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
