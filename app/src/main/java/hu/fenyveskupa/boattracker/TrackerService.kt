package hu.fenyveskupa.boattracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.regex.Pattern

class TrackerService : Service(), LocationListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var locationManager: LocationManager
    private var latestLocation: Location? = null
    private var trackingUrl: String? = null
    private var frequencySeconds = 30L
    private var broadcastInFlight = false
    private var hasAttemptedBroadcast = false

    private val broadcastRunnable = object : Runnable {
        override fun run() {
            broadcastLatestLocation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        ensureNotificationChannel()
        startForeground(1001, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TrackerIntents.ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            TrackerIntents.ACTION_START -> {
                trackingUrl = intent.getStringExtra(TrackerIntents.EXTRA_URL) ?: TrackerPrefs.trackingUrl(this)
                TrackerPrefs.setEnabled(this, true)
                hasAttemptedBroadcast = false
                startLocationUpdates()
            }
            else -> {
                trackingUrl = TrackerPrefs.trackingUrl(this)
                if (TrackerPrefs.enabled(this)) {
                    startLocationUpdates()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(broadcastRunnable)
        runCatching { locationManager.removeUpdates(this) }
        network.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TrackerPrefs.clearMessages(this)
        stopTracking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        TrackerPrefs.setPosition(this, location.latitude, location.longitude)
        announceStateChanged()
        if (!broadcastInFlight && !hasAttemptedBroadcast) {
            broadcastLatestLocation()
        }
    }

    @Deprecated("Deprecated in platform API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun startLocationUpdates() {
        if (!hasLocationPermission() || trackingUrl.isNullOrBlank()) {
            stopTracking()
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            if (locationManager.isProviderEnabled(provider)) {
                runCatching {
                    locationManager.requestLocationUpdates(provider, 5000L, 3f, this, Looper.getMainLooper())
                    locationManager.getLastKnownLocation(provider)?.let { location ->
                        if (latestLocation == null || location.time > (latestLocation?.time ?: 0L)) {
                            latestLocation = location
                            TrackerPrefs.setPosition(this, location.latitude, location.longitude)
                        }
                    }
                }
            }
        }
        announceStateChanged()
        latestLocation?.let { broadcastLatestLocation() }
    }

    private fun broadcastLatestLocation() {
        val url = trackingUrl ?: return
        val location = latestLocation ?: return
        if (!TrackerPrefs.enabled(this) || broadcastInFlight) return
        broadcastInFlight = true
        hasAttemptedBroadcast = true

        network.execute {
            var nextFrequency = frequencySeconds
            var message: String? = null
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                val body = buildRequestBody(location)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                parseFrequency(response)?.let { nextFrequency = it }
                message = parseMessage(response)
                connection.disconnect()
                TrackerPrefs.setBroadcastResult(this, message)
                if (message.isNullOrBlank()) {
                    TrackerPrefs.clearActiveMessageIfMatches(this, getString(R.string.communication_error))
                }
                if (!message.isNullOrBlank()) {
                    alertForNewMessage()
                }
            } catch (_: Exception) {
                TrackerPrefs.setActiveMessage(this, getString(R.string.communication_error))
            } finally {
                frequencySeconds = nextFrequency.coerceAtLeast(5L)
                broadcastInFlight = false
                announceStateChanged()
                scheduleNextBroadcast()
            }
        }
    }

    private fun scheduleNextBroadcast() {
        mainHandler.removeCallbacks(broadcastRunnable)
        if (TrackerPrefs.enabled(this)) {
            mainHandler.postDelayed(broadcastRunnable, frequencySeconds * 1000L)
        }
    }

    private fun stopTracking() {
        TrackerPrefs.setEnabled(this, false)
        announceStateChanged()
        stopSelf()
    }

    private fun parseFrequency(response: String): Long? {
        val matcher = Pattern.compile(""""f"\s*:\s*(\d+)""").matcher(response)
        return if (matcher.find()) matcher.group(1)?.toLongOrNull() else null
    }

    private fun buildRequestBody(location: Location): String {
        return """
            {
              "n": "${jsonEscape(TrackerPrefs.ship(this))}",
              "id": "${jsonEscape(TrackerPrefs.nevezesId(this))}",
              "la": ${location.latitude},
              "lo": ${location.longitude}
            }
        """.trimIndent()
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun parseMessage(response: String): String? {
        val matcher = Pattern.compile(""""msg"\s*:\s*"((?:\\.|[^"])*)"""").matcher(response)
        if (!matcher.find()) return null
        return matcher.group(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?.takeIf { it.isNotBlank() }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun announceStateChanged() {
        sendBroadcast(Intent(TrackerIntents.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun alertForNewMessage() {
        mainHandler.post {
            runCatching {
                val descriptor = assets.openFd("freesound_community-ocean-cruise-liner-ship-32308.mp3")
                val player = MediaPlayer()
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                descriptor.close()
                player.setOnCompletionListener { completedPlayer -> completedPlayer.release() }
                player.setOnErrorListener { failedPlayer, _, _ ->
                    failedPlayer.release()
                    true
                }
                player.prepare()
                player.start()
            }
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 250L, 120L, 250L), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0L, 250L, 120L, 250L), -1)
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tracking",
                getString(R.string.tracking_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "tracking")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(resources.getIdentifier("ic_launcher", "drawable", packageName))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
