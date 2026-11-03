package com.photovideo.gallerykz.memories.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The ad configuration: one Remote Config parameter ([PARAM_PREFIX] + the version code) holds a JSON with the profiles
 * `normal_user` and `paid_user`; the profile (normal by default, paid when [setProfile] says so) replaces the root. The last
 * good JSON is kept on the phone, so a cold start (a broadcast, the lock screen) has the config before the fetch is done.
 */
object AdRemoteConfig {

    /** Remote Config parameter = this + the app's version code (e.g. "Gallery_1"). */
    const val PARAM_PREFIX = "AppVersion_"
    const val PROFILE_NORMAL = "normal_user"
    const val PROFILE_PAID = "paid_user"

    private const val TAG = "KaziAds"
    private const val FILE = "ad_config"
    private const val KEY_JSON = "json"
    private const val KEY_PROFILE = "profile"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val REFRESH_MS = 12L * 60 * 60 * 1000

    @Volatile
    var config: AdConfig = AdConfig.EMPTY
        private set

    /** True once a config (saved or fetched) is in use. */
    @Volatile
    var loaded = false
        private set

    /** True once the first fetch of this start has ended, with or without success. */
    @Volatile
    var settled = false
        private set

    private val settleListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Runs [callback] on the main thread when the first fetch has ended (at once when it already has). */
    fun whenSettled(callback: () -> Unit) {
        if (settled) {
            main.post(callback)
        } else {
            settleListeners.add(callback)
            if (settled) main.post(callback)
        }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val fetching = java.util.concurrent.atomic.AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun profile(context: Context): String = prefs(context).getString(KEY_PROFILE, PROFILE_NORMAL) ?: PROFILE_NORMAL

    /** Switches between the normal and the paid profile (for example from the install referrer) and re-reads the saved JSON. */
    fun setProfile(context: Context, profile: String) {
        prefs(context).edit().putString(KEY_PROFILE, if (profile == PROFILE_PAID) PROFILE_PAID else PROFILE_NORMAL).apply()
        restore(context)
    }

    /** Loads the last saved JSON (no network). Safe to call from any thread, any number of times. */
    fun restore(context: Context) {
        val saved = prefs(context).getString(KEY_JSON, null) ?: return
        apply(context, saved, persist = false)
    }

    /** Fetches the parameter and applies it; skipped while another fetch runs. */
    fun fetch(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        if (!fetching.compareAndSet(false, true)) {
            onDone?.invoke(false)
            return
        }
        try {
            val remote = FirebaseRemoteConfig.getInstance()
            remote.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(0).build())
                .addOnCompleteListener {
                    remote.fetchAndActivate().addOnCompleteListener { task ->
                        var ok = false
                        try {
                            if (task.isSuccessful) {
                                val json = remote.getString(paramName(app))
                                Log.d(TAG, "fetch ok, key=" + paramName(app) + ", length=" + json.length)
                                if (json.isNotBlank()) {
                                    ok = apply(app, json, persist = true)
                                    prefs(app).edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "config fetch failed: $e")
                        } finally {
                            fetching.set(false)
                        }
                        main.post {
                            settled = true
                            settleListeners.forEach { it() }
                            settleListeners.clear()
                            onDone?.invoke(ok)
                        }
                    }
                }
        } catch (e: Exception) {
            fetching.set(false)
            Log.w(TAG, "config fetch could not start: $e")
            onDone?.invoke(false)
        }
    }

    /** Fetches again when the last fetch is older than 12 hours. */
    fun refreshIfDue(context: Context) {
        val last = prefs(context).getLong(KEY_FETCHED_AT, 0L)
        if (System.currentTimeMillis() - last > REFRESH_MS) fetch(context)
    }

    private fun apply(context: Context, raw: String, persist: Boolean): Boolean {
        return try {
            val text = raw.trim()
            val root = if (text.startsWith("[")) JSONArray(text).getJSONObject(0) else JSONObject(text)
            val chosen = resolveProfile(root, profile(context))
            config = AdConfig.parse(chosen)
            loaded = true
            if (persist) prefs(context).edit().putString(KEY_JSON, text).apply()
            main.post {
                AdCounters.onConfigApplied(context)
                listeners.forEach { it() }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "config could not be read: $e")
            false
        }
    }

    /** The profile object replaces the root (its value may be an object or a JSON text); a root without profiles is used as it is. */
    private fun resolveProfile(root: JSONObject, profile: String): JSONObject {
        val order = if (profile == PROFILE_PAID) listOf(PROFILE_PAID, PROFILE_NORMAL) else listOf(PROFILE_NORMAL, PROFILE_PAID)
        for (name in order) {
            val value = root.opt(name)
            if (value is JSONObject) return value
            if (value is String && value.isNotBlank()) return try {
                JSONObject(value)
            } catch (e: Exception) {
                continue
            }
        }
        return root
    }

    /** `AppVersion_` + the version name with dots as underscores: 1.0 reads `AppVersion_1_0`. */
    fun paramName(context: Context): String = PARAM_PREFIX + versionName(context).replace(".", "_")

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun versionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        1L
    }
}

/** The checks that decide whether an ad may appear: network, country, install day, and the "N per day" intervals. */
object AdGate {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun isNetworkAvailable(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (e: Exception) {
        false
    }

    /** Day 1 is the install day. */
    fun daysSinceInstall(context: Context): Int = try {
        val first = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        ((System.currentTimeMillis() - first) / DAY_MS).toInt() + 1
    } catch (e: Exception) {
        1
    }

    /** The country as an English name in upper case: the IP country when asked for and known, else the phone's. */
    fun countryName(context: Context, preferIp: Boolean = false): String {
        if (preferIp) IpLocation.country(context).takeIf { it.isNotEmpty() }?.let { return it.uppercase(Locale.ENGLISH) }
        val iso = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } ?: Locale.getDefault().country
        return Locale("", iso).getDisplayCountry(Locale.ENGLISH).uppercase(Locale.ENGLISH)
    }

    /** Empty lists mean everyone; names are English and compared case-insensitively; the exclude list wins. */
    fun countryAllowed(context: Context, show: List<String>, exclude: List<String>, preferIp: Boolean = false): Boolean {
        if (show.isEmpty() && exclude.isEmpty()) return true
        val country = countryName(context, preferIp)
        if (exclude.any { it.equals(country, true) }) return false
        return show.isEmpty() || show.any { it.equals(country, true) }
    }

    /** The global country lists of the config apply to every ad. */
    fun adsAllowed(context: Context): Boolean {
        val c = AdRemoteConfig.config
        return countryAllowed(context, c.showCountries, c.excludeCountries)
    }

    private fun times(context: Context) = context.applicationContext.getSharedPreferences("ad_last_show", Context.MODE_PRIVATE)

    fun lastShown(context: Context, key: String): Long = times(context).getLong(key, 0L)

    fun markShown(context: Context, key: String) {
        times(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }

    /** "N per day": at least 24 h / N since the last show. [zeroIsAlways] decides what N <= 0 means. */
    fun intervalElapsed(context: Context, key: String, perDay: Int, zeroIsAlways: Boolean): Boolean {
        if (perDay <= 0) return zeroIsAlways
        return System.currentTimeMillis() - lastShown(context, key) >= DAY_MS / perDay
    }
}

/** The user's location from the IP checker URL of the config (country, state, city), used for the Quiz link and the IP country gate. */
object IpLocation {

    private const val FILE = "ad_ip_location"
    private val executor = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun country(context: Context): String = prefs(context).getString("country", "") ?: ""
    fun state(context: Context): String = prefs(context).getString("state", "") ?: ""
    fun city(context: Context): String = prefs(context).getString("city", "") ?: ""

    /** Looks the location up once per call (in the background) when the config asks for it. */
    fun refresh(context: Context) {
        val proxy = AdRemoteConfig.config.proxy
        if (!proxy.checkIp || proxy.checkerUrl.isBlank()) return
        val app = context.applicationContext
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(proxy.checkerUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (json.optString("status", "success").equals("fail", true)) return@execute
                prefs(app).edit()
                    .putString("country", json.optString("country"))
                    .putString("state", json.optString("regionName"))
                    .putString("city", json.optString("city"))
                    .apply()
            } catch (e: Exception) {
                Log.w("KaziAds", "ip lookup failed: $e")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
