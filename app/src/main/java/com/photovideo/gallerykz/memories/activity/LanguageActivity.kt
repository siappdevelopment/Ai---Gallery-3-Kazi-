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

class LanguageActivity : AppCompatActivity() {

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

        if (viewModel.fromSettings) {
            // Opened from Settings: a back button.
            b.btnBack.visibility = android.view.View.VISIBLE
            b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        b.rvLanguages.layoutManager = LinearLayoutManager(this)
        b.rvLanguages.itemAnimator = null
        b.rvLanguages.adapter = adapter

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

    override fun onDestroy() {
        handler.removeCallbacks(showButton)
        binding?.btnContinue?.animate()?.cancel()
        binding?.rvLanguages?.adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val BUTTON_DELAY_MS = 500L
        private const val STATE_BUTTON_SHOWN = "button_shown"

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

    private fun go(tag: String) {
        // Finish first so a locale-triggered recreate never rebuilds this screen.
        finish()
        LocaleHelper.apply(tag)
        // From Settings the language is all that changes; during onboarding the next step follows.
        // The next screen of the configured flow follows.
        if (!viewModel.fromSettings) OnboardingFlow.advance(this, OnboardingFlow.Screen.LANGUAGE)
    }
}
