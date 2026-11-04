package com.photovideo.gallerykz.memories.ads

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * The base of the screens that count for the click interstitial (`inter_ads_click` with the screen's `on_click_inter_ads_show`): every
 * start of another in-app screen made from here (an activity, a fragment or a result launcher) passes through
 * [startActivityForResult], where the click counter of [ScreenInterAds] decides whether the interstitial comes first.
 * The screen block it counts for is set by `AdHooks.install` ([adScreen]); a screen without one never counts.
 */
open class AdAwareActivity : AppCompatActivity() {

    /** The Remote Config screen block this screen counts for (set by `AdHooks.install`); null = its clicks do not count. */
    var adScreen: AdScreen? = null

    /** The screen block a click made right now counts for; a pager home overrides it (only its gallery page counts). */
    protected open fun adScreenForClicks(): AdScreen? = adScreen

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        val screen = adScreenForClicks()
        if (screen == null || !opensCountedScreen(intent)) {
            super.startActivityForResult(intent, requestCode, options)
            return
        }
        ScreenInterAds.onClick(this, screen) {
            if (!isFinishing && !isDestroyed) super@AdAwareActivity.startActivityForResult(intent, requestCode, options)
        }
    }

    /** Opens [intent] without a click ad ("open the next screen and close this one": an ad cannot show over a closing screen). */
    fun startReplacing(intent: Intent) {
        super.startActivityForResult(intent, -1, null)
    }

    private fun opensCountedScreen(intent: Intent): Boolean {
        // Implicit intents (other apps, choosers, system settings) never count.
        val name = intent.component?.className ?: return false
        if (name.endsWith(".LauncherHomeActivity")) return false
        return try {
            AdAwareActivity::class.java.isAssignableFrom(Class.forName(name))
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
