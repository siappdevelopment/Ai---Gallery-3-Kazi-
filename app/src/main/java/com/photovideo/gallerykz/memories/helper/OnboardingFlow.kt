package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.content.Intent
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.launcher.LauncherHomeActivity

/**
 * The order of the onboarding screens after the splash: the Remote Config array `Show_Screen_Flow`
 * (DefaultApp, Language, PolicyScreen, Intro, MediaPermission, NotiPermission). Screens that are not in the array are skipped,
 * unknown and repeated names are ignored, and a missing or empty array uses the default order. Every screen calls [advance]
 * when it is done; after the last configured screen the launcher opens.
 */
object OnboardingFlow {

    /** A screen of the flow: its Remote Config name and the stored step that opens it. */
    enum class Screen(val key: String, val step: Int, vararg val aliases: String) {
        DEFAULT_APP("DefaultApp", OnboardingPrefs.STEP_DEFAULT, "DefaultAppScreen"),
        LANGUAGE("Language", OnboardingPrefs.STEP_LANGUAGE, "LanguageScreen"),
        POLICY("PolicyScreen", OnboardingPrefs.STEP_POLICY, "Policy", "PrivacyPolicy"),
        INTRO("Intro", OnboardingPrefs.STEP_INTRO1, "IntroScreen"),
        MEDIA("MediaPermission", OnboardingPrefs.STEP_MEDIA, "Media", "MediaPermissionScreen"),
        NOTI("NotiPermission", OnboardingPrefs.STEP_NOTI, "Notification", "NotificationPermission", "NotiPermissionScreen"),
    }

    /** The order used when the config has none (the order the app had before the array existed). */
    private val DEFAULT_ORDER = listOf(Screen.DEFAULT_APP, Screen.LANGUAGE, Screen.POLICY, Screen.MEDIA, Screen.NOTI, Screen.INTRO)

    private fun screenOfName(name: String): Screen? {
        val n = name.trim()
        return Screen.entries.firstOrNull { s -> s.key.equals(n, true) || s.aliases.any { it.equals(n, true) } }
    }

    /** The configured order without unknown names and duplicates; the default order when nothing valid is configured. */
    fun order(): List<Screen> {
        val parsed = LinkedHashSet<Screen>()
        for (name in AdRemoteConfig.config.screenFlow) screenOfName(name)?.let { parsed.add(it) }
        return if (parsed.isEmpty()) DEFAULT_ORDER else parsed.toList()
    }

    fun first(): Screen = order().first()

    /** The screen a stored step belongs to; null for the finished onboarding. */
    fun screenOfStep(step: Int): Screen? = when (step) {
        OnboardingPrefs.STEP_DEFAULT -> Screen.DEFAULT_APP
        OnboardingPrefs.STEP_LANGUAGE -> Screen.LANGUAGE
        OnboardingPrefs.STEP_POLICY -> Screen.POLICY
        OnboardingPrefs.STEP_INTRO1, OnboardingPrefs.STEP_INTRO2, OnboardingPrefs.STEP_INTRO3, OnboardingPrefs.STEP_INTRO4 -> Screen.INTRO
        OnboardingPrefs.STEP_MEDIA, OnboardingPrefs.STEP_MEDIA_DENIED -> Screen.MEDIA
        OnboardingPrefs.STEP_NOTI -> Screen.NOTI
        else -> null
    }

    /** The screen after [after]; null when the flow is over. A screen that left the configured flow continues with the next one in the default order. */
    fun next(after: Screen): Screen? {
        val order = order()
        val i = order.indexOf(after)
        if (i >= 0) return order.getOrNull(i + 1)
        val rank = DEFAULT_ORDER.indexOf(after)
        return order.firstOrNull { DEFAULT_ORDER.indexOf(it) > rank }
    }

    /**
     * The step to open for a stored [step]: the step itself while its screen is still in the configured flow, otherwise the step of the
     * next configured screen; [OnboardingPrefs.STEP_DONE] (stored, so the launcher does not send the user back) when nothing is left.
     */
    fun resolveStep(context: android.content.Context, step: Int): Int {
        val screen = screenOfStep(step) ?: return step
        if (screen in order()) return step
        val next = next(screen)
        if (next == null) {
            OnboardingPrefs.setStep(context, OnboardingPrefs.STEP_DONE)
            return OnboardingPrefs.STEP_DONE
        }
        OnboardingPrefs.setStep(context, next.step)
        return next.step
    }

    /**
     * Starts the screen after [after] (and stores its step), or the launcher when [after] was the last one. The caller finishes its
     * own screen. [bringForward] reuses a copy of the next screen that is already open (the system Home intent can have started it).
     */
    fun advance(activity: Activity, after: Screen, bringForward: Boolean = false) {
        val next = next(after)
        if (next == null) {
            OnboardingPrefs.setStep(activity, OnboardingPrefs.STEP_DONE)
            activity.startActivity(Intent(activity, LauncherHomeActivity::class.java))
            return
        }
        OnboardingPrefs.setStep(activity, next.step)
        activity.startActivity(if (bringForward) OnboardingRouter.resume(activity, next.step) else Intent(activity, OnboardingRouter.screenOf(next.step)))
    }
}
