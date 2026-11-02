package com.example.thirdgallery.helper

import android.view.View

/** The entrance of the three onboarding sheets (default app, photos, notifications): the raised option floats up, the sheet slides up. */
object OnboardingEnter {

    fun play(pill: View, sheet: View) {
        val density = pill.resources.displayMetrics.density
        pill.alpha = 0f
        pill.translationY = 28 * density
        sheet.translationY = 48 * density
        sheet.animate().translationY(0f).setDuration(380L).start()
        pill.animate().alpha(1f).translationY(0f).setStartDelay(160L).setDuration(420L).start()
    }
}
