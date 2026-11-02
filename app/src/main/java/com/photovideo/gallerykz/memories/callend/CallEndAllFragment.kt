package com.photovideo.gallerykz.memories.callend

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.activity.FullScreenMediaActivity
import com.photovideo.gallerykz.memories.databinding.FragmentCallEndAllBinding

/** The first tab of the call summary (the reference design): Photos / Videos, "Recent", and the media by day. */
class CallEndAllFragment : Fragment() {

    private var _binding: FragmentCallEndAllBinding? = null
    private val viewModel: CallEndAllViewModel by viewModels()
    private var lastOpenAt = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentCallEndAllBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _binding ?: return
        val adapter = CallEndMediaAdapter((resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1)) { uid -> openMedia(uid) }
        val manager = GridLayoutManager(requireContext(), COLUMNS)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.isHeader(position)) COLUMNS else 1
        }
        b.rvMedia.layoutManager = manager
        b.rvMedia.adapter = adapter
        b.rvMedia.itemAnimator = null

        b.chipPhotos.setOnClickListener { viewModel.selectVideos(false) }
        b.chipVideos.setOnClickListener { viewModel.selectVideos(true) }
        viewModel.showVideos.observe(viewLifecycleOwner) { showChips(b, it == true) }
        viewModel.media.observe(viewLifecycleOwner) { data -> adapter.submitData(viewLifecycleOwner.lifecycle, data) }
        adapter.addLoadStateListener { states ->
            val empty = states.refresh is LoadState.NotLoading && adapter.itemCount == 0
            _binding?.tvEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        _binding?.rvMedia?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /** The chosen chip is pink on a light pink fill with a pink outline; the other one is grey. */
    private fun showChips(b: FragmentCallEndAllBinding, videos: Boolean) {
        val on = ContextCompat.getColor(requireContext(), R.color.brand_pink)
        val off = ContextCompat.getColor(requireContext(), R.color.splash_text_secondary)
        b.chipPhotos.setBackgroundResource(if (videos) R.drawable.bg_call_end_chip_off else R.drawable.bg_call_end_chip_on)
        b.chipVideos.setBackgroundResource(if (videos) R.drawable.bg_call_end_chip_on else R.drawable.bg_call_end_chip_off)
        val photosColor = if (videos) off else on
        val videosColor = if (videos) on else off
        b.ivChipPhotos.setColorFilter(photosColor)
        b.tvChipPhotos.setTextColor(photosColor)
        b.ivChipVideos.setColorFilter(videosColor)
        b.tvChipVideos.setTextColor(videosColor)
    }

    private fun openMedia(uid: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(FullScreenMediaActivity.intent(requireContext(), uid, viewModel.kind, 0L))
    }

    companion object {
        private const val COLUMNS = 3
        private const val OPEN_GAP_MS = 600L
    }
}
