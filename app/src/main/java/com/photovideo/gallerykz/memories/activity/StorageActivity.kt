package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ActivityStorageBinding
import com.photovideo.gallerykz.memories.repo.StorageInfo
import com.photovideo.gallerykz.memories.viewmodel.StorageViewModel

/**
 * Storage (design): how much of the phone is used and available, a bar split into Photos, Videos and Other apps, and the
 * size of each. Photos and videos are the real file sizes of the library; "Other apps" is the rest of the used space.
 */
class StorageActivity : AdAwareActivity() {

    private var binding: ActivityStorageBinding? = null
    private val viewModel: StorageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityStorageBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.STORAGE)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        viewModel.info.observe(this) { render(it) }
        viewModel.loaded.observe(this) { loaded ->
            if (loaded == true && viewModel.info.value == null) render(null)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun render(info: StorageInfo?) {
        val b = binding ?: return
        b.card.visibility = if (info == null) View.GONE else View.VISIBLE
        b.tvError.visibility = if (info == null && viewModel.loaded.value == true) View.VISIBLE else View.GONE
        if (info == null) return

        b.tvUsed.text = Formatter.formatFileSize(this, info.used)
        b.tvOf.text = getString(R.string.storage_of, Formatter.formatFileSize(this, info.total))
        b.tvAvailable.text = getString(R.string.storage_available, Formatter.formatFileSize(this, info.available))
        b.tvPhotos.text = size(info.photos, info.mediaKnown)
        b.tvVideos.text = size(info.videos, info.mediaKnown)
        b.tvOther.text = Formatter.formatFileSize(this, info.other)
        b.tvFree.text = Formatter.formatFileSize(this, info.available)
        b.tvNoAccess.visibility = if (info.mediaKnown) View.GONE else View.VISIBLE

        // The bar: each part as wide as its share of the whole storage; the free part is the track behind it.
        val total = info.total.coerceAtLeast(1L).toFloat()
        setShare(b.barPhotos, info.photos / total)
        setShare(b.barVideos, info.videos / total)
        setShare(b.barOther, info.other / total)
        setShare(b.barFree, info.available / total)
    }

    private fun size(bytes: Long, known: Boolean): String =
        if (known) Formatter.formatFileSize(this, bytes) else getString(R.string.storage_unknown)

    private fun setShare(view: View, share: Float) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        params.weight = share.coerceIn(0f, 1f)
        view.layoutParams = params
        view.visibility = if (share > 0f) View.VISIBLE else View.GONE
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, StorageActivity::class.java)
    }
}
