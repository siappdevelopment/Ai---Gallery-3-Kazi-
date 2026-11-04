package com.photovideo.gallerykz.memories.callend

import com.photovideo.gallerykz.memories.ads.SlotSpec

import com.photovideo.gallerykz.memories.ads.BackAds

import com.photovideo.gallerykz.memories.ads.AdRemoteConfig

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityCallEndBinding
import com.google.android.material.tabs.TabLayoutMediator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The call summary (call-end-screen skill): who called, when and for how long, a button to open the dialer, and the skill's
 * four tabs (weather, message, reminder, more) with actions for this call.
 */
class CallEndActivity : AppCompatActivity() {

    private var binding: ActivityCallEndBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val b = ActivityCallEndBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.ivCall.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (e: Exception) {
                // No dialer to open.
            }
            finish()
        }

        b.vpData.adapter = CallEndPagerAdapter(this)
        b.vpData.offscreenPageLimit = CallEndPagerAdapter.TAB_COUNT
        // All, Message, Reminder, More (the skill's four tabs).
        TabLayoutMediator(b.tlOption, b.vpData) { tab, position ->
            val view = layoutInflater.inflate(R.layout.item_tab_icon_cl_end, null)
            view.findViewById<ImageView>(R.id.ivTabIcon).setImageResource(TAB_ICONS[position])
            view.findViewById<TextView>(R.id.tvTabName).setText(TAB_TITLES[position])
            tab.customView = view
            tab.contentDescription = getString(TAB_TITLES[position])
        }.attach()

        showCall(intent)
        setupAds()
    }

    /** The bottom ad (`ClEnd_Ad_*`) and the back ad (`ClEnd_Back_Ad_*`, sequence, day, per-day and country gates). */
    private fun setupAds() {
        try {
            val c = AdRemoteConfig.config.clEnd
            AdHooks.attachBottom(
                this,
                SlotSpec(show = c.adShow, native = c.native, bannerId = c.bannerId, nativeId = c.nativeId, large = true, cacheKey = "slot_clend"),
                navWithBigNative = true,
                // No Quiz ad on the call end screen: not at the bottom, not in the back ad, not as a fallback.
                noQuiz = true,
            )
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    BackAds.show(this@CallEndActivity, "clend", AdRemoteConfig.config.clEnd.back, "", noQuiz = true) {
                        if (!isFinishing) finish()
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.w("KaziAds", "call end ads failed to attach: $e")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showCall(intent)
    }

    override fun onResume() {
        super.onResume()
        CallEndNotifier.cancel(this)
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun showCall(intent: Intent) {
        val b = binding ?: return
        val end = intent.getLongExtra(EXTRA_END, System.currentTimeMillis())
        val start = intent.getLongExtra(EXTRA_START, end)
        val kind = CallKind.fromKey(intent.getStringExtra(EXTRA_KIND))
        CallEndLog.d("screen shown kind=${kind.key} end=$end durationMs=${end - start}")
        b.ivKind.setImageResource(kind.icon)
        b.tvNumber.text = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: getString(R.string.callend_unknown_number)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(end)).uppercase(Locale.getDefault())
        b.tvTimeType.text = getString(R.string.callend_type_time, getString(kind.label), time)
        val seconds = ((end - start).coerceAtLeast(0L)) / 1000
        b.tvDuration.text = getString(R.string.callend_duration, String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60))
    }

    companion object {
        const val EXTRA_NUMBER = "mobile_number"
        const val EXTRA_START = "start_ms"
        const val EXTRA_END = "end_ms"
        const val EXTRA_KIND = "call_kind"

        private val TAB_ICONS = listOf(R.drawable.ic_all_cl_end, R.drawable.ic_message_cl_end, R.drawable.ic_reminder_cl_end, R.drawable.ic_more_cl_end)
        private val TAB_TITLES = listOf(R.string.callend_all_title, R.string.callend_message_title, R.string.callend_reminder_title, R.string.callend_more_title)
    }
}
