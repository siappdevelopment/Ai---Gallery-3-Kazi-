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
import com.photovideo.gallerykz.memories.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The ad configuration: one Remote Config parameter (named by the build flavor, see [paramName]) holds a JSON with the profiles
 * `normal_user` and `paid_user`; the profile (paid when the install referrer holds a paid marker, see [profile]) replaces the root. The last
 * good JSON is kept on the phone, so a cold start (a broadcast, the lock screen) has the config before the fetch is done.
 */
object AdRemoteConfig {

    const val PROFILE_NORMAL = "normal_user"
    const val PROFILE_PAID = "paid_user"

    private const val TAG = "KaziAds"
    private const val FILE = "ad_config"
    private const val KEY_JSON = "json"
    private const val KEY_JSON_PARAM = "json_param"
    private const val KEY_REFERRER = "install_referrer"
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

    /** The profile this user should get: `paid_user` when the install referrer holds a paid marker, else `normal_user`. */
    fun profile(context: Context): String = referrerProfile(context)

    /**
     * Paid install = the Play install referrer contains one of these (lower-case) markers. Missing / empty referrer = normal user.
     * To support a new campaign naming, add a lower-case substring here.
     */
    private val PAID_MARKERS = listOf(
        "utm_source=apps.facebook.com",
        "utm_source=apps.instagram.com",
        "utm_source=marketing",
        "utm_medium=paid",
        "utm_medium=cpc",
        "gclid=",
    )

    private fun referrerProfile(context: Context): String {
        val referrer = (prefs(context).getString(KEY_REFERRER, "") ?: "").lowercase(Locale.ROOT)
        return if (referrer.isNotEmpty() && PAID_MARKERS.any { referrer.contains(it) }) PROFILE_PAID else PROFILE_NORMAL
    }

    /** Call once from Application.onCreate, after [restore]: reads the install referrer (once) and watches the network. */
    fun start(context: Context) {
        val app = context.applicationContext
        fetchReferrerIfNeeded(app)
        watchNetwork(app)
    }

    /**
     * Reads the Play install referrer once per install and saves it. OK: saved, then the saved config is applied again so the
     * right profile is used at once. FEATURE_NOT_SUPPORTED: saved as empty (never asked again, the user is normal). Anything else:
     * nothing saved, the next start tries again. Sideloaded / debug installs have no referrer.
     */
    private fun fetchReferrerIfNeeded(app: Context) {
        if (prefs(app).contains(KEY_REFERRER)) return
        try {
            val client = com.android.installreferrer.api.InstallReferrerClient.newBuilder(app).build()
            client.startConnection(object : com.android.installreferrer.api.InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    try {
                        when (code) {
                            com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val ref = client.installReferrer.installReferrer.orEmpty()
                                // gclid=CjwKCAiAxaCvBhBaEiwAvsLmWPrs37AYx_Ol2GN6T1nt1NHn2TJl7xf65ryNQtoWL-24BefvfIYBGRoCx-gQAvD_BwE
                                prefs(app).edit().putString(KEY_REFERRER, ref).apply()
                                Log.d(TAG, "install referrer read, profile=" + referrerProfile(app))
                                restore(app)
                            }
                            com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                                prefs(app).edit().putString(KEY_REFERRER, "").apply()
                            else -> Log.d(TAG, "install referrer not available: $code")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "install referrer read failed: $e")
                    } finally {
                        try { client.endConnection() } catch (_: Exception) { }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {}
            })
        } catch (e: Exception) {
            Log.d(TAG, "install referrer connect failed: $e")
        }
    }

    private var networkWatched = false
    private var networkLost = false

    /** When the network comes back after it was lost, the config is fetched again. */
    private fun watchNetwork(app: Context) {
        if (networkWatched) return
        networkWatched = true
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: android.net.Network) {
                    networkLost = true
                }

                override fun onAvailable(network: android.net.Network) {
                    if (!networkLost) return
                    networkLost = false
                    Log.d(TAG, "network is back, fetching the config again")
                    main.post { fetch(app) }
                }
            })
        } catch (e: Exception) {
            Log.d(TAG, "network watch failed: $e")
        }
    }

    /** Loads the last saved JSON (no network). Safe to call from any thread, any number of times. */
    fun restore(context: Context) {
        val p = prefs(context)
        val saved = p.getString(KEY_JSON, null) ?: return
        // The saved JSON belongs to the parameter it was fetched from: a QA build never starts from the production config
        // (or the other way round) that an earlier build of the same app left on the phone.
        if (p.getString(KEY_JSON_PARAM, null) != paramName(context)) return
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
        appContext = context.applicationContext
        return try {
            val text = raw.trim()
            val root = if (text.startsWith("[")) JSONArray(text).getJSONObject(0) else JSONObject(text)
            val wanted = profile(context)
            val chosen = resolveProfile(root, wanted)
            config = AdConfig.parse(chosen)
            loaded = true
            if (persist) prefs(context).edit().putString(KEY_JSON, text).putString(KEY_JSON_PARAM, paramName(context)).apply()
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
        for ((index, name) in order.withIndex()) {
            val obj = profileObject(root, name, 0) ?: continue
            Log.d(TAG, "wanted profile=$profile, using=$name, fallback=${index > 0}")
            setProfileProperty(name)
            return obj
        }
        Log.d(TAG, "wanted profile=$profile, using=root, fallback=true")
        setProfileProperty("root")
        return root
    }

    /** A profile value may be an inline object, a JSON text, or the name of another root key (followed up to depth 2). */
    private fun profileObject(root: JSONObject, name: String, depth: Int): JSONObject? {
        val value = root.opt(name)
        if (value is JSONObject) return value
        if (value is String && value.isNotBlank()) {
            try {
                return JSONObject(value)
            } catch (e: Exception) {
                if (depth < 2 && root.has(value)) return profileObject(root, value, depth + 1)
            }
        }
        return null
    }

    /** Analytics user property `ad_profile`: the block that was used (normal_user / paid_user / root). */
    private fun setProfileProperty(used: String) {
        try {
            com.google.firebase.analytics.FirebaseAnalytics.getInstance(appContext ?: return).setUserProperty("ad_profile", used)
        } catch (e: Exception) {
            Log.d(TAG, "ad_profile property failed: $e")
        }
    }

    private var appContext: Context? = null

    /**
     * The Remote Config parameter of this build, set per flavor in build.gradle.kts: prod reads `AppVersion_1_0`, qa reads
     * `AppVersion_Q_1` (both in the one Remote Config of the project).
     */
    @Suppress("UNUSED_PARAMETER")
    fun paramName(context: Context): String = BuildConfig.REMOTE_CONFIG_PARAM

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

    /**
     * The state / country lists of one screen's back ad (`show` = only there, `exclude` = never there; empty = everyone). Each entry is an
     * English name and is compared (case-insensitively) with the IP country, state and city when [useIp] is on and the IP lookup
     * (`App_Proxy_Check_Ip`) has an answer, else with the phone's country. The exclude list always wins over the show list.
     * Every step is logged under "KaziAds" so a decision can be followed.
     */
    fun locationAllowed(context: Context, tag: String, show: List<String>, exclude: List<String>, useIp: Boolean): Boolean {
        val proxy = AdRemoteConfig.config.proxy
        val phone = countryName(context, false)
        val ipCountry = IpLocation.country(context)
        val ipState = IpLocation.state(context)
        val ipCity = IpLocation.city(context)
        Log.d("KaziAds", "[$tag] location gate: show=$show exclude=$exclude useIp=$useIp App_Proxy_Check_Ip=${proxy.checkIp} checkerUrl=${if (proxy.checkerUrl.isBlank()) "(empty)" else proxy.checkerUrl}")
        Log.d("KaziAds", "[$tag] detected: ip country='$ipCountry' state='$ipState' city='$ipCity' phone country='$phone'")
        if (show.isEmpty() && exclude.isEmpty()) {
            Log.d("KaziAds", "[$tag] both lists empty -> allowed (everyone)")
            return true
        }
        val ipKnown = useIp && (ipCountry.isNotEmpty() || ipState.isNotEmpty() || ipCity.isNotEmpty())
        if (useIp && !ipKnown) Log.d("KaziAds", "[$tag] IP location not known yet (lookup off, failed or still running) -> using the phone country only")
        val places = if (ipKnown) listOf(ipCountry, ipState, ipCity) else listOf(phone)
        val here = places.map { it.trim() }.filter { it.isNotEmpty() }
        val excluded = exclude.firstOrNull { rule -> here.any { it.equals(rule.trim(), true) } }
        if (excluded != null) {
            Log.d("KaziAds", "[$tag] '$excluded' is in the exclude list -> NOT allowed (exclude wins)")
            return false
        }
        if (show.isEmpty()) {
            Log.d("KaziAds", "[$tag] show list empty and not excluded -> allowed")
            return true
        }
        val shown = show.firstOrNull { rule -> here.any { it.equals(rule.trim(), true) } }
        Log.d("KaziAds", "[$tag] show list " + if (shown != null) "has '$shown' -> allowed" else "does not have $here -> NOT allowed")
        return shown != null
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
    /** [force]: look up even when `App_Proxy_Check_Ip` is off (a screen with its own switch, like `ClEnd_Back_Ad_Country_IP`); the checker URL is still needed. */
    fun refresh(context: Context, force: Boolean = false) {
        val proxy = AdRemoteConfig.config.proxy
        if ((!force && !proxy.checkIp) || proxy.checkerUrl.isBlank()) return
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
