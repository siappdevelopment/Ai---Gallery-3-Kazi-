package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.helper.OnboardingFlow
import com.photovideo.gallerykz.memories.ads.OnboardingAds

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.photovideo.gallerykz.memories.adapter.LanguageAdapter
import com.photovideo.gallerykz.memories.databinding.ActivityLanguageBinding
import com.photovideo.gallerykz.memories.helper.LocaleHelper
import com.photovideo.gallerykz.memories.helper.OnboardingPrefs
import com.photovideo.gallerykz.memories.viewmodel.LanguageViewModel

class LanguageActivity : com.photovideo.gallerykz.memories.helper.BaseLocaleActivity() {

    private var binding: ActivityLanguageBinding? = null
    private val viewModel: LanguageViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var buttonShown = false
    private val showButton = Runnable { revealButton() }
    private val adapter = LanguageAdapter { viewModel.onLanguageSelected(it.tag) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        buttonShown = savedInstanceState?.getBoolean(STATE_BUTTON_SHOWN) == true
        live = java.lang.ref.WeakReference(this)

        val b = ActivityLanguageBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        // The large native ad sits below the whole screen (list and Continue button above it, never over them).
        AdHooks.install(this, AdScreen.LANGUAGE, large = true, navWithBigNative = true)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // In the onboarding Back does nothing; from Settings it goes back (the back button above).
        if (!viewModel.fromSettings) OnboardingFlow.blockBack(this)
        if (viewModel.fromSettings) {
            // Opened from Settings: a back button.
            b.btnBack.visibility = android.view.View.VISIBLE
            b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        b.rvLanguages.layoutManager = LinearLayoutManager(this)
        b.rvLanguages.itemAnimator = null
        b.rvLanguages.adapter = adapter
        setupListCorners(b)

        b.etSearch.doAfterTextChanged { viewModel.onSearchChanged(it?.toString().orEmpty()) }
        b.btnContinue.setOnClickListener { viewModel.onContinue() }

        viewModel.items.observe(this) { list ->
            adapter.submitList(list)
            b.tvEmpty.isVisible = list.isEmpty()
        }
        viewModel.texts.observe(this) { t ->
            b.tvTitle.setTextIfChanged(t.title)
            b.tvSubtitle.setTextIfChanged(t.subtitle)
            b.tvEmpty.setTextIfChanged(t.noResults)
            b.btnContinue.setTextIfChanged(t.continueLabel)
            if (b.etSearch.hint?.toString() != t.searchHint) b.etSearch.hint = t.searchHint
        }
        viewModel.canContinue.observe(this) { enabled ->
            // The button appears 500 ms after the first selection and then stays, whatever is chosen next.
            if (enabled && !buttonShown) {
                handler.removeCallbacks(showButton)
                handler.postDelayed(showButton, BUTTON_DELAY_MS)
            }
            if (buttonShown) revealButton()
        }
        viewModel.navigateNext.observe(this) { event ->
            event.getContentIfNotHandled()?.let { tag -> openNext(tag) }
        }
    }

    // ---- List card corners: with a bottom ad the card stays flat at the bottom until the last language is reached ----
    private val cardBackground = android.graphics.drawable.GradientDrawable()
    private var cornersAnimator: android.animation.ValueAnimator? = null
    private var bottomRadius = -1f
    private var bottomTarget = -1f

    private fun setupListCorners(b: ActivityLanguageBinding) {
        cardBackground.setColor(androidx.core.content.ContextCompat.getColor(this, com.photovideo.gallerykz.memories.R.color.language_card))
        b.cardList.background = cardBackground
        setBottomRadius(fullRadius())
        val update = { updateListCorners() }
        b.rvLanguages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) = update()
        })
        // The list's size changes when the ad appears / disappears or the keyboard moves it; the content when the search changes.
        b.rvLanguages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        b.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    }

    private fun fullRadius() = 20f * resources.displayMetrics.density

    private fun setBottomRadius(bottom: Float) {
        bottomRadius = bottom
        val top = fullRadius()
        cardBackground.cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
    }

    /** The bottom ad strip is the second child of the wrapper that [AdHooks] put around this screen (visible only while an ad is). */
    private fun isBottomAdVisible(): Boolean {
        val wrapper = binding?.root?.parent as? android.view.ViewGroup ?: return false
        val strip = wrapper.takeIf { it.childCount > 1 }?.getChildAt(1) ?: return false
        return strip.visibility == android.view.View.VISIBLE
    }

    private fun updateListCorners() {
        val b = binding ?: return
        if (isFinishing || isDestroyed || b.rvLanguages.height == 0) return
        val atEnd = !b.rvLanguages.canScrollVertically(1)
        val target = if (!isBottomAdVisible() || atEnd) fullRadius() else 0f
        if (target == bottomTarget) return
        val first = bottomTarget < 0f
        bottomTarget = target
        cornersAnimator?.cancel()
        if (first) {
            setBottomRadius(target)
            b.cardList.invalidate()
            return
        }
        cornersAnimator = android.animation.ValueAnimator.ofFloat(bottomRadius, target).apply {
            duration = 180L
            addUpdateListener { setBottomRadius(it.animatedValue as Float) }
            start()
        }
    }

    private fun revealButton() {
        val button = binding?.btnContinue ?: return
        if (isFinishing || isDestroyed) return
        buttonShown = true
        if (button.isVisible) return
        button.alpha = 0f
        button.isVisible = true
        button.animate().alpha(1f).setDuration(200L).start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BUTTON_SHOWN, buttonShown)
    }

    /** Not while Continue is in progress: the ad must stay on this screen and the choice is still unsaved. */
    override fun shouldRecreateForLocale(): Boolean = !viewModel.isContinuing

    override fun onDestroy() {
        if (live?.get() === this) live = null
        cornersAnimator?.cancel()
        handler.removeCallbacks(showButton)
        binding?.btnContinue?.animate()?.cancel()
        binding?.rvLanguages?.adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val BUTTON_DELAY_MS = 500L
        private const val STATE_BUTTON_SHOWN = "button_shown"

        /** The copy of this screen that is on screen now (an older copy can still receive the late ad callback). */
        private var live: java.lang.ref.WeakReference<LanguageActivity>? = null

        fun intent(context: android.content.Context, fromSettings: Boolean = false): Intent =
            Intent(context, LanguageActivity::class.java).putExtra(LanguageViewModel.ARG_FROM_SETTINGS, fromSettings)
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (text?.toString() != value) text = value
    }

    private fun openNext(tag: String) {
        // Onboarding: the Language interstitial (when due) comes before the next step; from Settings there is none.
        if (viewModel.fromSettings) go(tag) else OnboardingAds.afterLanguage(this) { go(tag) }
    }

    private var advanced = false

    private fun go(tag: String) {
        // Once only: a late ad callback must never move the onboarding on a second time.
        if (advanced) return
        advanced = true
        // The ad flow is over: only now the choice (and the next step) is written.
        viewModel.commitSelection(tag)
        // Finish first so a locale-triggered recreate never rebuilds this screen.
        finish()
        // If the system rebuilt this screen while the ad was showing, the live copy must close too (else it stays underneath).
        live?.get()?.takeIf { it !== this && !it.isFinishing }?.finish()
        LocaleHelper.apply(tag)
        // From Settings the language is all that changes; during onboarding the next step follows.
        // The next screen of the configured flow follows.
        if (!viewModel.fromSettings) OnboardingFlow.advance(this, OnboardingFlow.Screen.LANGUAGE)
    }
}
