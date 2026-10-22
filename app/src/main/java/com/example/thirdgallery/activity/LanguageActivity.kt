package com.example.thirdgallery.activity

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.thirdgallery.adapter.LanguageAdapter
import com.example.thirdgallery.databinding.ActivityLanguageBinding
import com.example.thirdgallery.helper.LocaleHelper
import com.example.thirdgallery.viewmodel.LanguageViewModel

class LanguageActivity : AppCompatActivity() {

    private var binding: ActivityLanguageBinding? = null
    private val viewModel: LanguageViewModel by viewModels()
    private val adapter = LanguageAdapter { viewModel.onLanguageSelected(it.tag) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityLanguageBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
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
            b.btnContinue.isEnabled = enabled
            b.btnContinue.alpha = if (enabled) 1f else 0.5f
        }
        viewModel.navigateNext.observe(this) { event ->
            event.getContentIfNotHandled()?.let { tag -> openNext(tag) }
        }
    }

    override fun onDestroy() {
        binding?.rvLanguages?.adapter = null
        binding = null
        super.onDestroy()
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (text?.toString() != value) text = value
    }

    private fun openNext(tag: String) {
        // Finish first so a locale-triggered recreate never rebuilds this screen.
        finish()
        LocaleHelper.apply(tag)
        startActivity(Intent(this, Intro1Activity::class.java))
    }
}
