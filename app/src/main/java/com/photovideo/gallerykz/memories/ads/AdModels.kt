package com.photovideo.gallerykz.memories.ads

import org.json.JSONArray
import org.json.JSONObject

/** Every screen that has an ad block in the Remote Config (object name under "Screen" and the prefix of its keys). */
/**
 * [backByDefault]: the screen's block has no `on_back_inter_ads_show` key in the Remote Config (the Main / gallery pages and the
 * cleaning report), so its Back / Done interstitial follows the master `inter_ads_on_back` flag alone.
 */
enum class AdScreen(val obj: String, val prefix: String, val nativeOnly: Boolean = false, val backByDefault: Boolean = false) {
    SPLASH("SplashScreen", "Splash"),
    DEFAULT_APP("DefaultAppScreen", "DefaultApp"),
    LANGUAGE("LanguageScreen", "Language"),
    INTRO("IntroScreen", "Intro"),
    MEDIA_PERMISSION("MediaPermissionScreen", "MediaPermission"),
    NOTI_PERMISSION("NotiPermissionScreen", "NotiPermission"),
    MAIN("MainScreen", "Main", backByDefault = true),
    GALLERY("GalleryScreen", "Gallery", nativeOnly = true, backByDefault = true),
    ALBUMS("AlbumsScreen", "Albums", nativeOnly = true, backByDefault = true),
    FOR_YOU("ForYouScreen", "ForYou_Highlights", nativeOnly = true, backByDefault = true),
    ALBUM_DETAIL("AlbumDetailScreen", "AlbumDetail"),
    CATEGORY_MEDIA("CategoryMediaScreen", "CategoryMedia"),
    VIEWER("ViewerScreen", "Viewer"),
    VIDEO_PLAYER("VideoPlayerScreen", "VideoPlayer"),
    EDITOR("EditorScreen", "Editor"),
    SEARCH("SearchScreen", "Search"),
    TRASH("TrashScreen", "Trash"),
    VAULT_SETUP("VaultSetupScreen", "VaultSetup"),
    VAULT_PIN("VaultPinScreen", "VaultPin"),
    VAULT("VaultScreen", "Vault"),
    VAULT_SETTING("VaultSettingScreen", "VaultSetting"),
    PEOPLE("PeopleScreen", "People"),
    PLACES("PlacesScreen", "Places"),
    SETTINGS("SettingsScreen", "Settings"),
    LAUNCHER_SETTINGS("LauncherSettingsScreen", "LauncherSettings"),
    ABOUT("AboutScreen", "About"),
    WIDGETS("WidgetsScreen", "Widgets"),
    HIGHLIGHT("HighlightScreen", "Highlight"),
    STORAGE("StorageScreen", "Storage"),
}

/** One slot of a screen: the bottom ad (banner or native) and the interstitial switches of that screen. */
class ScreenAd(
    val show: Boolean = false,
    val native: Boolean = true,
    val bannerId: String = "",
    val nativeId: String = "",
    val onBackInter: Boolean = false,
    val onClickInter: Boolean = false,
    val autoRefresh: Boolean = false,
    val autoSeconds: Int = 0,
)

enum class SeqType { G_INTER, G_APPOPEN, G_NATIVE, Q_INTER, Q_APPOPEN, Q_NATIVE, Q_BROWSER }

class SeqStep(val type: SeqType, val count: Int)

/** The back / Done ad of the event screens, the Recent page and the Call End screen. */
class BackAdCfg(
    val show: Boolean = false,
    val steps: List<SeqStep> = emptyList(),
    val interId: String = "",
    val fullNativeId: String = "",
    val afterDays: Int = 0,
    val perDay: Int = 0,
    val showCountries: List<String> = emptyList(),
    val excludeCountries: List<String> = emptyList(),
    val useIpCountry: Boolean = false,
)

/** Charging, install / uninstall and Recent page blocks. */
class EventAdCfg(
    val bottomShow: Boolean = false,
    val preload: Boolean = false,
    val bottomNative: Boolean = true,
    val nativeId: String = "",
    val bannerId: String = "",
    val buttonSec: Int = 0,
    val back: BackAdCfg = BackAdCfg(),
)

class InterGlobal(
    val clickShow: Boolean = false,
    val clickEvery: Int = 0,
    val clickPreload: Boolean = false,
    val backShow: Boolean = false,
    val backEvery: Int = 0,
    val backPreload: Boolean = false,
    val clickId: String = "",
    val backId: String = "",
    val nativePreload: Boolean = false,
    val bannerPreload: Boolean = false,
)

class SwipeCfg(
    val show: Boolean = false,
    val preload: Boolean = false,
    /** Every Nth swipe; 0 = every swipe. */
    val every: Int = 0,
    val steps: List<SeqStep> = emptyList(),
    val interId: String = "",
    val nativeId: String = "",
    val failedQuiz: Boolean = false,
)

class MainAd(
    val bottomNavShow: Boolean = false,
    val bottomNavPreload: Boolean = false,
    val bottomNavId: String = "",
    val bottomNavEvery: Int = 0,
    val right: SwipeCfg = SwipeCfg(),
    val left: SwipeCfg = SwipeCfg(),
)

class TapAdCfg(
    val show: Boolean = false,
    val every: Int = 0,
    val steps: List<SeqStep> = emptyList(),
    val interId: String = "",
    val nativeId: String = "",
)

class LauncherAd(
    val listShow: Boolean = false,
    val listPerDay: Int = 0,
    val listId: String = "",
    val reportShow: Boolean = false,
    val reportPerDay: Int = 0,
    val reportId: String = "",
    val tap: TapAdCfg = TapAdCfg(),
    val back: TapAdCfg = TapAdCfg(),
    val quizIconShow: Boolean = false,
    val quizIconCount: Int = 0,
    val gameFolderShow: Boolean = false,
    val unlockShow: Boolean = false,
    val unlockId: String = "",
    val unlockPerDay: Int = 0,
    val unlockPreload: Boolean = false,
    val unlockSteps: List<SeqStep> = emptyList(),
    val unlockInterId: String = "",
    val unlockNativeId: String = "",
    val unlockFailedQuiz: Boolean = false,
    val unlockCountries: List<String> = emptyList(),
    val unlockExclude: List<String> = emptyList(),
)

class ClEndAd(
    val adShow: Boolean = false,
    val native: Boolean = false,
    val bannerId: String = "",
    val nativeId: String = "",
    val back: BackAdCfg = BackAdCfg(),
    val notificationInstallDays: Int = 0,
    val notificationBackShow: Boolean = false,
    val notificationCloseButton: Boolean = false,
)

class QuizData(
    val appIcons: List<String> = emptyList(),
    val nativeMedia: List<String> = emptyList(),
    val interMedia: List<String> = emptyList(),
    val appOpenMedia: List<String> = emptyList(),
    val bannerTitle: List<String> = emptyList(),
    val bannerDesc: List<String> = emptyList(),
    val nativeTitle: List<String> = emptyList(),
    val nativeDesc: List<String> = emptyList(),
    val interTitle: List<String> = emptyList(),
    val interDesc: List<String> = emptyList(),
    val buttonText: String = "",
) {
    /** How many quiz ads can be built: 0 when any needed list is empty, else at most 3. */
    val syncedCount: Int
        get() {
            val lists = listOf(appIcons, bannerTitle, bannerDesc, nativeTitle, nativeDesc, interTitle, interDesc)
            if (lists.any { it.isEmpty() }) return 0
            return minOf(3, lists.minOf { it.size })
        }
}

class ProxyData(
    val checkIp: Boolean = false,
    val checkerUrl: String = "",
    val links: List<String> = emptyList(),
    val excludeLinks: List<String> = emptyList(),
    val cities: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
)

class SplashAd(
    val durationSec: Int = 0,
    val afterShow: Boolean = false,
    val afterAppOpen: Boolean = false,
    val afterInterId: String = "",
)

class AdConfig(
    val loaded: Boolean = false,
    val priorityQuiz: Boolean = false,
    val googleFailedQuiz: Boolean = false,
    val googleFailBrowser: Boolean = false,
    val labelColor: String = "",
    val buttonLight: String = "",
    val buttonDark: String = "",
    val appOpenId: String = "",
    val appOpenShow: Boolean = false,
    val appOpenDialog: Boolean = false,
    val appOpenPerDay: Int = 0,
    val clEndScreenShow: Boolean = true,
    /** `Privacy_Policy` of the Remote Config: the page the privacy policy link opens. */
    val privacyPolicyUrl: String = "",
    /** `Show_Screen_Flow`: the onboarding screens after the splash, in order (empty = the default order). */
    val screenFlow: List<String> = emptyList(),
    val showCountries: List<String> = emptyList(),
    val excludeCountries: List<String> = emptyList(),
    val inter: InterGlobal = InterGlobal(),
    val screens: Map<AdScreen, ScreenAd> = emptyMap(),
    val splash: SplashAd = SplashAd(),
    val afterDefaultShow: Boolean = false,
    val afterDefaultAppOpen: Boolean = false,
    val languageInterShow: Boolean = false,
    val introInterShow: Boolean = false,
    /** `Intro_Screen_Count`: how many intro screens to show (0 / missing = all). */
    val introCount: Int = 0,
    val main: MainAd = MainAd(),
    val launcher: LauncherAd = LauncherAd(),
    val clEnd: ClEndAd = ClEndAd(),
    val charging: EventAdCfg = EventAdCfg(),
    val install: EventAdCfg = EventAdCfg(),
    val recent: EventAdCfg = EventAdCfg(),
    val chargeInShow: Boolean = true,
    val chargeOutShow: Boolean = true,
    val installShow: Boolean = true,
    val uninstallShow: Boolean = true,
    val quiz: QuizData = QuizData(),
    val proxy: ProxyData = ProxyData(),
) {
    fun screen(screen: AdScreen): ScreenAd = screens[screen] ?: ScreenAd()

    /** The unit of the Language, Intro and After-default interstitials: Other id, else the click interstitial id. */
    val onboardingInterId: String get() = inter.clickId

    companion object {
        val EMPTY = AdConfig()

        fun parse(root: JSONObject): AdConfig {
            val screen = root.optJSONObject("Screen") ?: JSONObject()
            val inter = root.optJSONObject("inter_ads") ?: JSONObject()
            val quizBlock = screen.optJSONObject("QuizAdsDesign") ?: JSONObject()
            val proxyBlock = screen.optJSONObject("AppProxyStructure") ?: root.optJSONObject("AppProxyStructure") ?: JSONObject()
            val main = screen.optJSONObject("MainScreen") ?: JSONObject()
            val launcherBlock = screen.optJSONObject("LauncherAppScreen") ?: JSONObject()
            val clEnd = screen.optJSONObject("ClEndScreen") ?: JSONObject()
            val splash = screen.optJSONObject("SplashScreen") ?: JSONObject()
            val defaultApp = screen.optJSONObject("DefaultAppScreen") ?: JSONObject()
            val language = screen.optJSONObject("LanguageScreen") ?: JSONObject()
            val intro = screen.optJSONObject("IntroScreen") ?: JSONObject()

            val screens = HashMap<AdScreen, ScreenAd>()
            for (s in AdScreen.entries) {
                val block = screen.optJSONObject(s.obj) ?: continue
                val p = s.prefix
                // `banner` / `native` as configured; a missing or unknown type uses the screen default (Language: native, others: banner).
                val type = block.str("${p}_Ad_Type").trim().lowercase()
                val isNative = when (type) {
                    "native" -> true
                    "banner" -> false
                    else -> s == AdScreen.LANGUAGE
                }
                screens[s] = ScreenAd(
                    show = block.bool("${p}_Ad_Show"),
                    native = s.nativeOnly || isNative,
                    bannerId = block.str("${p}_Banner_Id"),
                    nativeId = block.str("${p}_Native_Id"),
                    onBackInter = if (block.has("on_back_inter_ads_show")) block.bool("on_back_inter_ads_show") else s.backByDefault,
                    onClickInter = block.bool("on_click_inter_ads_show"),
                    autoRefresh = block.bool("${p}_Ad_Auto_Refresh"),
                    autoSeconds = block.int("${p}_Ad_Auto_Second"),
                )
            }
            // Main also has its own auto-refresh names.
            screens[AdScreen.MAIN]?.let {
                screens[AdScreen.MAIN] = ScreenAd(
                    it.show, it.native, it.bannerId, it.nativeId, it.onBackInter, it.onClickInter,
                    main.bool("Main_Bottom_Ad_Auto_Refresh"), main.int("Main_Bottom_Ad_Auto_Second"),
                )
            }

            val failedQuizRoot = root.bool("Google_Ad_Failed_Show_Quiz") || quizBlock.bool("Google_Ad_Failed_Show_Quiz")
            val priority = quizBlock.str("Ad_Priority").ifEmpty { root.str("Ad_Priority") }
            return AdConfig(
                loaded = true,
                priorityQuiz = priority.equals("QUIZ", true),
                googleFailedQuiz = failedQuizRoot,
                googleFailBrowser = root.str("Google_ad_Fail_type").equals("Browser", true),
                labelColor = root.str("Native_Ad_Label_Color"),
                buttonLight = root.str("Native_Ad_Button_Color_light"),
                buttonDark = root.str("Native_Ad_Button_Color_dark"),
                appOpenId = root.str("App_Open_Id"),
                appOpenShow = root.bool("App_Open_Ad_Show"),
                appOpenDialog = root.bool("App_Open_Dialog_Show"),
                appOpenPerDay = root.int("App_Open_Show_Per_Day"),
                clEndScreenShow = root.bool("Cl_End_Screen_Show", true),
                privacyPolicyUrl = root.str("Privacy_Policy").trim(),
                screenFlow = root.list("Show_Screen_Flow"),
                showCountries = root.list("Ads_Show_countryList"),
                excludeCountries = root.list("Ads_show_List_Exclude"),
                inter = InterGlobal(
                    clickShow = inter.bool("inter_ads_show"),
                    clickEvery = inter.int("inter_ads_click"),
                    clickPreload = inter.str("inter_ads_click_type").equals("preload", true),
                    backShow = inter.bool("inter_ads_on_back"),
                    backEvery = inter.int("inter_ads_back_click"),
                    backPreload = inter.str("inter_ads_back_click_type").equals("preload", true),
                    clickId = inter.str("inter_ads_id"),
                    backId = inter.str("inter_back_ads_id").ifEmpty { inter.str("inter_ads_id") },
                    nativePreload = inter.str("native_ads_type").equals("preload", true),
                    bannerPreload = inter.str("banner_ads_type").equals("preload", true),
                ),
                screens = screens,
                splash = SplashAd(
                    durationSec = splash.int("Splash_Duration"),
                    afterShow = splash.bool("After_Splash_Ad_Show"),
                    afterAppOpen = splash.str("After_Splash_Ad_Type").equals("appopen", true),
                    afterInterId = splash.str("After_Splash_Interstitial_Id").ifEmpty { inter.str("inter_ads_id") },
                ),
                afterDefaultShow = defaultApp.bool("After_Default_Ad_Show") || defaultApp.bool("After_default_Ad_Show"),
                afterDefaultAppOpen = defaultApp.str("After_Default_Ad_Type").equals("appopen", true) ||
                    defaultApp.str("After_default_Ad_Type").equals("appopen", true),
                languageInterShow = language.bool("Language_Interstitial_Ad_Show"),
                introInterShow = intro.bool("Intro_Interstitial_Ad_Show"),
                introCount = intro.int("Intro_Screen_Count"),
                main = MainAd(
                    bottomNavShow = main.bool("bottom_nav_inter_ads_show"),
                    bottomNavPreload = main.str("bottom_nav_inter_ads_type").equals("preload", true),
                    bottomNavId = main.str("bottom_nav_inter_ads_id"),
                    bottomNavEvery = main.int("bottom_nav_inter_click"),
                    right = swipe(main, "Right_Swipe", failedQuizRoot),
                    left = swipe(main, "Left_Swipe", failedQuizRoot),
                ),
                launcher = LauncherAd(
                    listShow = launcherBlock.bool("LauncherApp_Native_List_Ad_Show"),
                    listPerDay = launcherBlock.int("LauncherApp_Native_List_Ad_Show_Per_Day"),
                    listId = launcherBlock.str("LauncherApp_Native_List_Id"),
                    reportShow = launcherBlock.bool("CleaningReport_Native_Ad_Show"),
                    reportPerDay = launcherBlock.int("CleaningReport_Native_Ad_Show_Per_Day"),
                    reportId = launcherBlock.str("CleaningReport_Native_Id"),
                    tap = TapAdCfg(
                        show = launcherBlock.bool("LauncherApp_Click_Ad_Show"),
                        every = launcherBlock.int("LauncherApp_Count"),
                        steps = parseSteps(launcherBlock.opt("LauncherApp_Ad_Type")),
                        interId = launcherBlock.str("LauncherApp_Interstitial_Id").ifEmpty { inter.str("inter_ads_id") },
                        nativeId = launcherBlock.str("LauncherApp_Native_Id"),
                    ),
                    back = TapAdCfg(
                        show = launcherBlock.bool("LauncherApp_Back_Click_Ad_Show"),
                        every = launcherBlock.int("LauncherApp_Back_Count"),
                        steps = parseSteps(launcherBlock.opt("LauncherApp_Back_Ad_Type")),
                        interId = launcherBlock.str("LauncherApp_Back_Interstitial_Id").ifEmpty { inter.str("inter_ads_id") },
                        nativeId = launcherBlock.str("LauncherApp_Back_Native_Id"),
                    ),
                    quizIconShow = launcherBlock.bool("LauncherApp_Quiz_Icon_Show"),
                    quizIconCount = launcherBlock.int("LauncherApp_Quiz_Icon_Count"),
                    gameFolderShow = launcherBlock.bool("LauncherApp_Game_Folder_Show"),
                    unlockShow = launcherBlock.bool("Unlock_App_Open_Show"),
                    unlockId = launcherBlock.str("Unlock_App_Open_Id").ifEmpty { root.str("App_Open_Id") },
                    unlockPerDay = launcherBlock.int("Unlock_App_Open_Per_Day"),
                    unlockPreload = launcherBlock.str("Unlock_App_Open_Ads_type").equals("preload", true),
                    unlockSteps = parseSteps(launcherBlock.opt("Unlock_App_Open_Ad_Sequence")),
                    unlockInterId = launcherBlock.str("Unlock_App_Open_Interstitial_Id").ifEmpty { inter.str("inter_ads_id") },
                    unlockNativeId = launcherBlock.str("Unlock_App_Open_Native_Id"),
                    unlockFailedQuiz = launcherBlock.bool("Unlock_App_Open_Failed_Show_Quiz") || failedQuizRoot,
                    unlockCountries = launcherBlock.list("Unlock_Ad_Show_Country"),
                    unlockExclude = launcherBlock.list("Unlock_Ad_Show_Exclude_Location"),
                ),
                clEnd = ClEndAd(
                    adShow = clEnd.bool("ClEnd_Ad_Show"),
                    native = !clEnd.str("ClEnd_Ad_Type", "banner").equals("banner", true),
                    bannerId = clEnd.str("ClEnd_Banner_Id"),
                    nativeId = clEnd.str("ClEnd_Native_Id"),
                    back = BackAdCfg(
                        show = clEnd.bool("ClEnd_Back_Ad_Show"),
                        steps = parseSteps(clEnd.opt("ClEnd_Back_Ad_Sequence")),
                        interId = clEnd.str("ClEnd_Back_Ad_Interstitial_Id").ifEmpty { inter.str("inter_ads_id") },
                        fullNativeId = clEnd.str("ClEnd_Back_Ad_Native_Id"),
                        afterDays = clEnd.int("ClEnd_Back_Ad_Show_After_Day"),
                        perDay = clEnd.int("ClEnd_Back_Ad_Show_Per_Day"),
                        showCountries = clEnd.list("ClEnd_Back_Ad_Show_Country"),
                        excludeCountries = clEnd.list("ClEnd_Back_Ad_Exclude_Country"),
                        useIpCountry = clEnd.bool("ClEnd_Back_Ad_Country_IP"),
                    ),
                    notificationInstallDays = clEnd.int("Notification_Install_Days"),
                    notificationBackShow = clEnd.bool("Notification_Back_Ad_Show"),
                    notificationCloseButton = clEnd.bool("Notification_Close_Button_Show"),
                ),
                charging = event(screen.optJSONObject("charging_screen") ?: root.optJSONObject("charging_screen"), "charging_screen", inter),
                install = event(screen.optJSONObject("install_uninstall_screen") ?: root.optJSONObject("install_uninstall_screen"), "install_uninstall_screen", inter),
                recent = event(screen.optJSONObject("recent_screen"), "recent_screen", inter),
                chargeInShow = (screen.optJSONObject("charging_screen") ?: root.optJSONObject("charging_screen"))?.bool("charge_in_screen_show") ?: true,
                chargeOutShow = (screen.optJSONObject("charging_screen") ?: root.optJSONObject("charging_screen"))?.bool("charge_out_screen_show") ?: true,
                installShow = (screen.optJSONObject("install_uninstall_screen") ?: root.optJSONObject("install_uninstall_screen"))?.bool("install_screen_show") ?: true,
                uninstallShow = (screen.optJSONObject("install_uninstall_screen") ?: root.optJSONObject("install_uninstall_screen"))?.bool("uninstall_screen_show") ?: true,
                quiz = QuizData(
                    appIcons = quizBlock.list("Quiz_App_Icon"),
                    nativeMedia = quizBlock.list("Quiz_Native_Media"),
                    interMedia = quizBlock.list("Quiz_Interstitial_Media"),
                    appOpenMedia = quizBlock.list("Quiz_App_Open_Media"),
                    bannerTitle = quizBlock.list("Quiz_Banner_Title"),
                    bannerDesc = quizBlock.list("Quiz_Banner_Description"),
                    nativeTitle = quizBlock.list("Quiz_Native_Title"),
                    nativeDesc = quizBlock.list("Quiz_Native_Description"),
                    interTitle = quizBlock.list("Quiz_Interstitial_Title"),
                    interDesc = quizBlock.list("Quiz_Interstitial_Description"),
                    buttonText = quizBlock.str("Quiz_Button_Text"),
                ),
                proxy = ProxyData(
                    checkIp = proxyBlock.bool("App_Proxy_Check_Ip"),
                    checkerUrl = proxyBlock.str("App_Proxy_Ip_Checker_Url"),
                    links = proxyBlock.list("Quiz_Link_List"),
                    excludeLinks = proxyBlock.list("Quiz_Link_List_Exclude"),
                    cities = proxyBlock.list("App_Proxy_List_City"),
                    states = proxyBlock.list("App_Proxy_List_State"),
                    countries = proxyBlock.list("App_Proxy_List_Country"),
                ),
            )
        }

        private fun swipe(main: JSONObject, p: String, failedRoot: Boolean) = SwipeCfg(
            show = main.bool("${p}_Interstitial_Ad_Show"),
            preload = main.str("${p}_Ads_type").equals("preload", true),
            every = main.int("${p}_Interstitial"),
            steps = parseSteps(main.opt("${p}_Ad_Sequence")),
            interId = main.str("${p}_Interstitial_Id"),
            nativeId = main.str("${p}_Native_Id"),
            failedQuiz = main.bool("${p}_Failed_Show_Quiz") || failedRoot,
        )

        private fun event(block: JSONObject?, p: String, inter: JSONObject): EventAdCfg {
            val b = block ?: return EventAdCfg()
            return EventAdCfg(
                bottomShow = b.bool("${p}_bottom_ad_show"),
                preload = b.str("${p}_ad_load_type").equals("preload", true),
                bottomNative = !b.str("${p}_bottom_ad_type", "native").equals("banner", true),
                nativeId = b.str("${p}_native_id"),
                bannerId = b.str("${p}_banner_id"),
                buttonSec = b.int("${p}_button_show_sec"),
                back = BackAdCfg(
                    show = b.bool("${p}_back_ad_show"),
                    steps = parseSteps(b.opt("${p}_back_ad_sequence")),
                    interId = b.str("${p}_inter_id").ifEmpty { inter.str("inter_ads_id") },
                    fullNativeId = b.str("${p}_full_native_id"),
                    afterDays = b.int("${p}_back_ad_day_count"),
                    perDay = b.int("${p}_back_ad_total_show_count"),
                    showCountries = b.list("${p}_back_ads_show_country"),
                    excludeCountries = b.list("${p}_back_ads_exclude_country"),
                ),
            )
        }

        /** A sequence: `["Quiz_Browser", ["Google_Inter", 1], "inter,2"]`, or one type as a string. */
        fun parseSteps(value: Any?): List<SeqStep> {
            val out = ArrayList<SeqStep>()
            fun add(type: String, count: Int) {
                val t = seqType(type) ?: return
                out.add(SeqStep(t, count.coerceAtLeast(1)))
            }
            fun addText(text: String) {
                if (text.contains(',')) add(text.substringBefore(','), text.substringAfter(',').trim().toIntOrNull() ?: 1) else add(text, 1)
            }
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    when (val e = value.opt(i)) {
                        is JSONArray -> add(e.optString(0), e.optString(1, "1").trim().toIntOrNull() ?: 1)
                        is String -> addText(e)
                    }
                }
                is String -> addText(value)
            }
            return out
        }

        private fun seqType(raw: String): SeqType? = when (raw.lowercase().replace("_", "").replace(" ", "").replace("-", "")) {
            "googleinter", "inter", "interstitial" -> SeqType.G_INTER
            "googleappopen", "appopen" -> SeqType.G_APPOPEN
            "googlenative", "native", "fullnative" -> SeqType.G_NATIVE
            "quizinter" -> SeqType.Q_INTER
            "quizappopen" -> SeqType.Q_APPOPEN
            "quiznative" -> SeqType.Q_NATIVE
            "quizbrowser" -> SeqType.Q_BROWSER
            else -> null
        }
    }
}

internal fun JSONObject.str(key: String, def: String = ""): String =
    if (has(key) && !isNull(key)) optString(key, def).trim() else def

internal fun JSONObject.bool(key: String, def: Boolean = false): Boolean =
    if (has(key) && !isNull(key)) optBoolean(key, def) else def

internal fun JSONObject.int(key: String, def: Int = 0): Int =
    if (has(key) && !isNull(key)) optInt(key, def) else def

/** A list of strings: a JSON array, or one text with the items separated by `|`. */
internal fun JSONObject.list(key: String): List<String> {
    val v = opt(key)
    val out = ArrayList<String>()
    when (v) {
        is JSONArray -> for (i in 0 until v.length()) v.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" }?.let(out::add)
        is String -> v.split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach(out::add)
    }
    return out
}
