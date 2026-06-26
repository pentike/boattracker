package hu.fenyveskupa.boattracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private companion object {
        const val HEADER_PHOTO_URL = "https://fenyvesvit.hu/sites/fenyvesvit.hu/themes/adt_higherground/images/header-photo.jpg"
        const val COLOR_BACKGROUND = 0xFFFAFBFD.toInt()
        const val COLOR_TEXT = 0xFF001F1C.toInt()
        const val COLOR_TITLE = 0xFF002F2A.toInt()
        const val COLOR_NAVY = 0xFF071F49.toInt()
        const val COLOR_NAVY_DARK = 0xFF031633.toInt()
        const val COLOR_GOLD = 0xFFF8C316.toInt()
        const val COLOR_HEADER_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_MUTED = 0xFF4F635E.toInt()
        const val COLOR_MESSAGE = 0xFF003D33.toInt()
        const val COLOR_WARNING = 0xFF4B2A00.toInt()
        const val TEXT_TITLE = 24f
        const val TEXT_EVENT = 20f
        const val TEXT_BODY = 19f
        const val TEXT_LABEL = 15f
        const val TEXT_VALUE = 23f
        const val TEXT_SHIP = 29f
        const val TEXT_MESSAGE = 19f
    }

    private lateinit var eventValue: TextView
    private lateinit var mottoValue: TextView
    private lateinit var shipValue: TextView
    private lateinit var coordinatesValue: TextView
    private lateinit var messageSection: LinearLayout
    private lateinit var messageValue: TextView
    private lateinit var warningSection: TextView
    private lateinit var messageListTitle: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var radioTowerView: RadioTowerView
    private lateinit var broadcastSwitch: Switch
    private lateinit var headerPhoto: ImageView
    private var suppressSwitchCallback = false

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
            handleDirectStartup()
            return
        }
        val config = parseTrackerConfig(startupUrl) ?: return
        initializeBackend(config)
    }

    private fun handleDirectStartup() {
        val savedConfig = TrackerPrefs.savedConfig(this)
        if (savedConfig == null) {
            fetchStartupEvents()
            return
        }
        showShipConfirmationDialog(savedConfig)
    }

    private fun showShipConfirmationDialog(config: TrackerConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_ship_title)
            .setMessage(getString(R.string.confirm_ship_message, config.shipName))
            .setPositiveButton(R.string.confirm_ship_approve) { _, _ ->
                initializeBackend(config.copy(shipName = TrackerPrefs.ship(this)))
            }
            .setNegativeButton(R.string.confirm_ship_change) { _, _ ->
                showShipNameDialog(config)
            }
            .show()
    }

    private fun showShipNameDialog(config: TrackerConfig) {
        val input = EditText(this).apply {
            setText(config.shipName.takeUnless { it == getString(R.string.unknown_value) }.orEmpty())
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_ship_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val shipName = input.text.toString().trim().ifBlank { getString(R.string.unknown_value) }
                TrackerPrefs.setShip(this, shipName)
                initializeBackend(config.copy(shipName = shipName))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun configureSystemBars() {
        window.statusBarColor = COLOR_NAVY_DARK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
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
        configureSystemBars()
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
        configureSystemBars()
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
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        addSampleHeader(content)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(body)

        addBoatStatusCard(body)

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
        body.addView(messageSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        warningSection = TextView(this).apply {
            setText(R.string.broadcast_off_warning)
            textSize = TEXT_MESSAGE
            setTextColor(COLOR_WARNING)
            background = getDrawable(R.drawable.warning_background)
            includeFontPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        body.addView(warningSection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        addRulesSection(body)

        messageListTitle = TextView(this).apply {
            setText(R.string.messages)
            textSize = TEXT_LABEL
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            setPadding(0, dp(18), 0, dp(8))
            visibility = View.GONE
        }
        body.addView(messageListTitle)

        messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(messageList)

        setContentView(root)
        configureSystemBars()
    }

    private fun addSampleHeader(parent: LinearLayout) {
        val header = FrameLayout(this).apply {
            setBackgroundColor(COLOR_NAVY_DARK)
        }
        headerPhoto = ImageView(this).apply {
            setImageResource(R.drawable.rule_port_starboard)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.header_photo)
        }
        header.addView(headerPhoto, FrameLayout.LayoutParams(dp(226), -1, Gravity.END))
        header.addView(HeaderOverlayView(this), FrameLayout.LayoutParams(-1, -1))

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(16), dp(18), dp(18))
        }
        eventValue = TextView(this).apply {
            textSize = 29f
            setTextColor(COLOR_HEADER_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 3
            setLineSpacing(dp(4).toFloat(), 1f)
            visibility = View.GONE
        }
        copy.addView(eventValue, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        mottoValue = TextView(this).apply {
            textSize = 18f
            setTextColor(COLOR_HEADER_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 2
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        copy.addView(mottoValue, LinearLayout.LayoutParams(-1, -2))
        header.addView(copy, FrameLayout.LayoutParams(dp(248), -1, Gravity.START))

        header.addView(languageButton(COLOR_HEADER_TEXT), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            rightMargin = dp(8)
        })
        parent.addView(header, LinearLayout.LayoutParams(-1, dp(188)))
        loadHeaderPhoto()
    }

    private fun addBoatStatusCard(parent: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.boat_status_card_background)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        info.addView(statusLabel(R.string.ship))
        shipValue = TextView(this).apply {
            setText(R.string.unknown_value)
            textSize = TEXT_SHIP
            setTextColor(COLOR_NAVY)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(shipValue, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        info.addView(View(this).apply {
            setBackgroundColor(0xFFD3D8E2.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(10) })

        info.addView(statusLabel(R.string.current_gps))
        coordinatesValue = TextView(this).apply {
            setText(R.string.unknown_value)
            textSize = TEXT_VALUE
            setTextColor(COLOR_NAVY)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 2
        }
        info.addView(coordinatesValue)
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })

        val transmission = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = getDrawable(R.drawable.transmission_panel_background)
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }
        radioTowerView = RadioTowerView(this)
        transmission.addView(radioTowerView, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            bottomMargin = dp(16)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val switchLabel = TextView(this).apply {
            setText(R.string.broadcast_position)
            textSize = TEXT_LABEL
            setTextColor(COLOR_NAVY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
        }
        transmission.addView(switchLabel, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(18)
        })
        broadcastSwitch = Switch(this).apply {
            scaleX = 1.35f
            scaleY = 1.35f
            thumbTintList = switchThumbColors()
            trackTintList = switchTrackColors()
            trackDrawable?.setColorFilter(COLOR_GOLD, PorterDuff.Mode.SRC_IN)
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
        transmission.addView(broadcastSwitch, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        card.addView(transmission, LinearLayout.LayoutParams(dp(132), -1))

        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    private fun statusLabel(labelRes: Int): TextView {
        return TextView(this).apply {
            text = getString(labelRes).uppercase()
            textSize = TEXT_LABEL
            setTextColor(0xFF6D727C.toInt())
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            letterSpacing = 0.02f
        }
    }

    private fun switchThumbColors(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.WHITE),
        )
    }

    private fun switchTrackColors(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(COLOR_GOLD, COLOR_GOLD),
        )
    }

    private fun languageButton(textColor: Int = COLOR_TITLE): TextView {
        return TextView(this).apply {
            text = currentLanguageFlag()
            textSize = 24f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            contentDescription = getString(R.string.language)
            isClickable = true
            isFocusable = true
            setOnClickListener { showLanguageDialog() }
        }
    }

    private fun currentLanguageFlag(): String {
        return when (TrackerPrefs.language(this)) {
            "hu" -> "🇭🇺"
            else -> "🇬🇧"
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

    private fun renderState() {
        val event = TrackerPrefs.event(this)
        eventValue.text = event
        eventValue.visibility = if (event.isBlank()) View.GONE else View.VISIBLE
        val motto = TrackerPrefs.motto(this)
        mottoValue.text = motto
        mottoValue.visibility = if (motto.isBlank()) View.GONE else View.VISIBLE
        shipValue.text = TrackerPrefs.ship(this).uppercase()
        coordinatesValue.text = getString(
            R.string.coordinates_degrees_format,
            TrackerPrefs.latitude(this),
            TrackerPrefs.longitude(this),
        )
        val msg = TrackerPrefs.message(this)
        messageSection.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
        messageValue.text = msg
        renderMessageList()
        val enabled = TrackerPrefs.enabled(this)
        warningSection.visibility = if (enabled) View.GONE else View.VISIBLE
        radioTowerView.setBroadcasting(enabled)
        suppressSwitchCallback = true
        broadcastSwitch.isChecked = enabled
        suppressSwitchCallback = false
    }

    private fun loadHeaderPhoto() {
        thread {
            try {
                val connection = URL(HEADER_PHOTO_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.inputStream.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        runOnUiThread {
                            headerPhoto.setImageBitmap(bitmap)
                        }
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                // The bundled fallback remains visible when the remote photo is unavailable.
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

    private fun addRulesSection(parent: LinearLayout) {
        val title = TextView(this).apply {
            setText(R.string.racing_rules)
            textSize = TEXT_LABEL
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            setPadding(0, dp(18), 0, dp(8))
        }
        parent.addView(title)
        addRuleCard(
            parent,
            R.drawable.rule_port_starboard,
            R.string.rule_10_title,
            R.string.rule_10_description,
        )
        addRuleCard(
            parent,
            R.drawable.rule_windward_leeward,
            R.string.rule_11_title,
            R.string.rule_11_description,
        )
        addRuleCard(
            parent,
            R.drawable.rule_overtaking,
            R.string.rule_12_title,
            R.string.rule_12_description,
        )
        addRuleCard(
            parent,
            R.drawable.rule_tacking,
            R.string.rule_13_title,
            R.string.rule_13_description,
        )
        addRulesLink(parent)
    }

    private fun addRuleCard(parent: LinearLayout, imageRes: Int, titleRes: Int, descriptionRes: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.rule_card_background)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, RuleDetailActivity::class.java)
                        .putExtra(RuleDetailActivity.EXTRA_IMAGE_RES, imageRes)
                        .putExtra(RuleDetailActivity.EXTRA_TITLE_RES, titleRes)
                        .putExtra(RuleDetailActivity.EXTRA_DESCRIPTION_RES, descriptionRes),
                )
            }
        }
        val image = ImageView(this).apply {
            setImageResource(imageRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        card.addView(image, LinearLayout.LayoutParams(dp(104), dp(72)).apply {
            rightMargin = dp(10)
        })
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            setText(titleRes)
            textSize = TEXT_LABEL
            setTextColor(COLOR_TITLE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        copy.addView(title)
        val description = TextView(this).apply {
            setText(descriptionRes)
            textSize = TEXT_LABEL
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        }
        copy.addView(description)
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }

    private fun addRulesLink(parent: LinearLayout) {
        val link = TextView(this).apply {
            setText(R.string.rules_book_link)
            textSize = TEXT_LABEL
            setTextColor(COLOR_MESSAGE)
            includeFontPadding = false
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(dp(10), dp(4), dp(10), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.rules_book_url))))
            }
        }
        parent.addView(link)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class HeaderOverlayView(context: Context) : View(context) {
        private val shape = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            paint.color = COLOR_BACKGROUND
            shape.reset()
            shape.moveTo(0f, h - 26f)
            shape.cubicTo(w * 0.28f, h - 9f, w * 0.68f, h - 8f, w, h - 23f)
            shape.lineTo(w, h)
            shape.lineTo(0f, h)
            shape.close()
            canvas.drawPath(shape, paint)

            paint.color = COLOR_NAVY_DARK
            shape.reset()
            shape.moveTo(0f, 0f)
            shape.lineTo(w * 0.66f, 0f)
            shape.lineTo(w * 0.505f, h - 26f)
            shape.lineTo(0f, h - 44f)
            shape.close()
            canvas.drawPath(shape, paint)

            paint.color = COLOR_GOLD
            shape.reset()
            shape.moveTo(0f, h - 40f)
            shape.cubicTo(w * 0.28f, h - 23f, w * 0.68f, h - 22f, w, h - 36f)
            shape.lineTo(w, h - 28f)
            shape.cubicTo(w * 0.68f, h - 14f, w * 0.28f, h - 15f, 0f, h - 32f)
            shape.close()
            canvas.drawPath(shape, paint)
        }
    }

    private class RadioTowerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_NAVY
            strokeWidth = 2.8f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        private val waveBounds = RectF()
        private var broadcasting = false
        private var phase = 0f

        fun setBroadcasting(enabled: Boolean) {
            val changed = broadcasting != enabled
            broadcasting = enabled
            if (changed) {
                phase = 0f
            }
            alpha = if (enabled) 1f else 0.48f
            if (enabled) {
                postInvalidateOnAnimation()
            } else {
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width / 48f, height / 48f)
            canvas.save()
            canvas.translate((width - 48f * scale) / 2f, (height - 48f * scale) / 2f)
            canvas.scale(scale, scale)
            paint.strokeWidth = 2.8f
            paint.alpha = 255
            paint.style = Paint.Style.STROKE

            drawLine(canvas, 24f, 18f, 13f, 42f)
            drawLine(canvas, 24f, 18f, 35f, 42f)
            drawLine(canvas, 17f, 33f, 31f, 33f)
            drawLine(canvas, 20f, 27f, 28f, 27f)
            drawLine(canvas, 15f, 42f, 33f, 42f)
            canvas.drawCircle(24f, 18f, 4f, paint)

            drawWave(canvas, left = true, inner = true, alpha = waveAlpha(0.66f))
            drawWave(canvas, left = false, inner = true, alpha = waveAlpha(0.66f))
            drawWave(canvas, left = true, band = 1, alpha = waveAlpha(0.33f))
            drawWave(canvas, left = false, band = 1, alpha = waveAlpha(0.33f))
            drawWave(canvas, left = true, band = 2, alpha = waveAlpha(0f))
            drawWave(canvas, left = false, band = 2, alpha = waveAlpha(0f))
            canvas.restore()

            if (broadcasting) {
                phase = (phase + 0.004375f) % 1f
                postInvalidateOnAnimation()
            }
        }

        private fun drawLine(canvas: Canvas, startX: Float, startY: Float, stopX: Float, stopY: Float) {
            canvas.drawLine(startX, startY, stopX, stopY, paint)
        }

        private fun waveAlpha(offset: Float): Int {
            if (!broadcasting) return 255
            val progress = (phase + offset) % 1f
            return (255 * (1f - progress)).toInt().coerceIn(0, 255)
        }

        private fun drawWave(canvas: Canvas, left: Boolean, inner: Boolean = false, band: Int = 0, alpha: Int) {
            paint.alpha = alpha
            val inset = when {
                inner -> 13f
                band == 1 -> 8f
                else -> 3f
            }
            val top = when {
                inner -> 12f
                band == 1 -> 8f
                else -> 4f
            }
            val bottom = when {
                inner -> 24f
                band == 1 -> 29f
                else -> 34f
            }
            if (left) {
                waveBounds.set(inset, top, 31f, bottom)
                canvas.drawArc(waveBounds, 135f, 90f, false, paint)
            } else {
                waveBounds.set(17f, top, 48f - inset, bottom)
                canvas.drawArc(waveBounds, -45f, 90f, false, paint)
            }
        }
    }
}
