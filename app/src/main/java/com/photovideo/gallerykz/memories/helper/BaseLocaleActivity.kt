package com.photovideo.gallerykz.memories.helper

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base of every screen of the app. It puts the app's language on the screen itself instead of waiting for AppCompat or the
 * system to deliver it, so a screen opened right after the language was chosen (or a Home screen that was already open
 * and is brought back) never shows the phone's language.
 */
open class BaseLocaleActivity : AppCompatActivity() {

    private var staleChecked = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        staleChecked = false
    }

    /** A screen in the middle of a flow that must not be rebuilt (an ad is showing over it) returns false. */
    protected open fun shouldRecreateForLocale(): Boolean = true

    override fun onResume() {
        super.onResume()
        // A copy that was created before the language changed (a singleTask Home brought forward, a screen kept in the
        // background) is rebuilt once in the right language.
        if (!staleChecked && shouldRecreateForLocale() && LocaleHelper.isStale(this)) {
            staleChecked = true
            recreate()
        }
    }
}
