package hu.fenyveskupa.boattracker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TrackerConfig(
    val initUrl: String,
    val postUrl: String,
    val domain: String,
    val shipName: String,
    val nevezesId: String,
    val logoUrl: String = "",
    val event: String = "",
    val motto: String = "",
)

data class StartupEvent(
    val name: String,
    val start: String,
    val configUrl: String,
)

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val time: Long,
)

object TrackerPrefs {
    const val DEFAULT_INIT_URL = "https://fenyveskupa.hu/api/pozicio/init"
    const val STARTUP_URL = "https://fenyveskupa.hu/boattracker/startup"

    private const val NAME = "tracker"
    private const val KEY_INIT_URL = "initUrl"
    private const val KEY_POST_URL = "postUrl"
    private const val KEY_DOMAIN = "domain"
    private const val KEY_SHIP = "ship"
    private const val KEY_NEVEZES_ID = "nevezesId"
    private const val KEY_LOGO_URL = "logoUrl"
    private const val KEY_EVENT = "event"
    private const val KEY_MOTTO = "motto"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_LAST = "last"
    private const val KEY_MESSAGE = "message"
    private const val KEY_MESSAGES = "messages"
    private const val KEY_TRAJECTORY = "trajectory"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LANGUAGE = "language"
    private const val MAX_MESSAGES = 20
    private const val MAX_TRAJECTORY_POINTS = 1000

    fun saveConfig(context: Context, config: TrackerConfig) {
        prefs(context).edit()
            .putString(KEY_INIT_URL, config.initUrl)
            .putString(KEY_POST_URL, config.postUrl)
            .putString(KEY_DOMAIN, config.domain)
            .putString(KEY_SHIP, config.shipName)
            .putString(KEY_NEVEZES_ID, config.nevezesId)
            .putString(KEY_LOGO_URL, config.logoUrl)
            .putString(KEY_EVENT, config.event)
            .putString(KEY_MOTTO, config.motto)
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    fun trackingUrl(context: Context): String? = prefs(context).getString(KEY_POST_URL, null)
    fun initUrl(context: Context): String = prefs(context).getString(KEY_INIT_URL, DEFAULT_INIT_URL) ?: DEFAULT_INIT_URL
    fun domain(context: Context): String = prefs(context).getString(KEY_DOMAIN, "-") ?: "-"
    fun ship(context: Context): String = prefs(context).getString(KEY_SHIP, "-") ?: "-"
    fun nevezesId(context: Context): String = prefs(context).getString(KEY_NEVEZES_ID, "") ?: ""
    fun logoUrl(context: Context): String = prefs(context).getString(KEY_LOGO_URL, "") ?: ""
    fun event(context: Context): String = prefs(context).getString(KEY_EVENT, "") ?: ""
    fun motto(context: Context): String = prefs(context).getString(KEY_MOTTO, "") ?: ""
    fun latitude(context: Context): String = prefs(context).getString(KEY_LAT, "-") ?: "-"
    fun longitude(context: Context): String = prefs(context).getString(KEY_LON, "-") ?: "-"
    fun lastBroadcast(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)
    fun message(context: Context): String = prefs(context).getString(KEY_MESSAGE, "") ?: ""
    fun messages(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_MESSAGES, null) ?: return emptyList()
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                json.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
    fun trajectory(context: Context): List<TrackPoint> {
        val raw = prefs(context).getString(KEY_TRAJECTORY, null) ?: return emptyList()
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    TrackPoint(
                        latitude = item.optDouble("la"),
                        longitude = item.optDouble("lo"),
                        time = item.optLong("t"),
                    ),
                )
            }
        }.filter { it.latitude.isFinite() && it.longitude.isFinite() }
    }
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, "hu") ?: "hu"

    fun savedConfig(context: Context): TrackerConfig? {
        val initUrl = prefs(context).getString(KEY_INIT_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val postUrl = prefs(context).getString(KEY_POST_URL, null)?.takeIf { it.isNotBlank() } ?: initUrl
        return TrackerConfig(
            initUrl = initUrl,
            postUrl = postUrl,
            domain = domain(context),
            shipName = ship(context),
            nevezesId = nevezesId(context),
            logoUrl = logoUrl(context),
            event = event(context),
            motto = motto(context),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun setShip(context: Context, ship: String) {
        prefs(context).edit().putString(KEY_SHIP, ship).apply()
    }

    fun localizedContext(context: Context): Context {
        val locale = Locale(language(context))
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }

    fun setPosition(context: Context, lat: Double, lon: Double) {
        prefs(context).edit()
            .putString(KEY_LAT, "%.6f".format(Locale.US, lat))
            .putString(KEY_LON, "%.6f".format(Locale.US, lon))
            .apply()
    }

    fun addSentPosition(context: Context, lat: Double, lon: Double, time: Long = System.currentTimeMillis()) {
        val next = (trajectory(context) + TrackPoint(lat, lon, time)).takeLast(MAX_TRAJECTORY_POINTS)
        val json = JSONArray()
        next.forEach { point ->
            json.put(
                JSONObject()
                    .put("la", point.latitude)
                    .put("lo", point.longitude)
                    .put("t", point.time),
            )
        }
        prefs(context).edit().putString(KEY_TRAJECTORY, json.toString()).apply()
    }

    fun clearTrajectory(context: Context) {
        prefs(context).edit().putString(KEY_TRAJECTORY, "[]").apply()
    }

    fun setBroadcastResult(context: Context, message: String?) {
        val editor = prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis())
        if (!message.isNullOrBlank()) {
            editor.putString(KEY_MESSAGE, message)
            editor.putString(KEY_MESSAGES, appendMessage(context, message).toString())
        }
        editor.apply()
    }

    fun setActiveMessage(context: Context, message: String) {
        prefs(context).edit().putString(KEY_MESSAGE, message).apply()
    }

    fun clearMessage(context: Context) {
        prefs(context).edit().putString(KEY_MESSAGE, "").apply()
    }

    fun clearActiveMessageIfMatches(context: Context, message: String) {
        if (message(context) == message) {
            clearMessage(context)
        }
    }

    fun clearMessages(context: Context) {
        prefs(context).edit()
            .putString(KEY_MESSAGE, "")
            .putString(KEY_MESSAGES, "[]")
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun appendMessage(context: Context, message: String): JSONArray {
        val existing = messages(context)
        val next = listOf(message) + existing.filterNot { it == message }
        return JSONArray(next.take(MAX_MESSAGES))
    }
}

object TrackerIntents {
    const val ACTION_START = "hu.fenyveskupa.boattracker.START"
    const val ACTION_STOP = "hu.fenyveskupa.boattracker.STOP"
    const val ACTION_STATE_CHANGED = "hu.fenyveskupa.boattracker.STATE_CHANGED"
    const val EXTRA_URL = "url"
}

fun parseTrackerConfig(rawData: String?): TrackerConfig? {
    val data = rawData.takeUnless { it.isNullOrBlank() } ?: return null
    val rawTrackingUrl = if (data.startsWith("boattracker:", ignoreCase = true)) {
        Uri.decode(data.substringAfter("boattracker:"))
    } else {
        data
    }
    val uri = Uri.parse(rawTrackingUrl)
    val host = uri.host ?: return null
    val scheme = uri.scheme ?: return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val domain = "$scheme://$host$port"
    val shipName = uri.getQueryParameter("hajo") ?: "-"
    val nevezesId = uri.getQueryParameter("nevezesId") ?: ""
    return TrackerConfig(
        initUrl = rawTrackingUrl,
        postUrl = rawTrackingUrl,
        domain = domain,
        shipName = shipName,
        nevezesId = nevezesId,
    )
}

fun parseStartupEvents(response: String): List<StartupEvent> {
    val events = JSONObject(response).getJSONArray("events")
    return buildList {
        for (index in 0 until events.length()) {
            val item = events.getJSONObject(index)
            add(
                StartupEvent(
                    name = item.optString("name"),
                    start = item.optString("start"),
                    configUrl = item.optString("configUrl"),
                ),
            )
        }
    }.filter { it.name.isNotBlank() && it.configUrl.isNotBlank() }
}

fun applyInitResponse(config: TrackerConfig, response: String): TrackerConfig {
    val json = JSONObject(response)
    val postUrl = json.optString("url", config.postUrl).ifBlank { config.postUrl }
    val postUri = Uri.parse(postUrl)
    val host = postUri.host ?: Uri.parse(config.initUrl).host.orEmpty()
    val scheme = postUri.scheme ?: Uri.parse(config.initUrl).scheme.orEmpty()
    val port = if (postUri.port == -1) "" else ":${postUri.port}"
    val domain = if (scheme.isBlank() || host.isBlank()) config.domain else "$scheme://$host$port"
    return config.copy(
        postUrl = postUrl,
        domain = domain,
        logoUrl = json.optString("logo", ""),
        event = json.optString("event", ""),
        motto = json.optString("motto", ""),
    )
}
