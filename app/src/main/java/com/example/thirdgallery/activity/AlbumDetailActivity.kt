package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.databinding.ActivityAlbumDetailBinding
import com.example.thirdgallery.helper.GalleryLayoutPrefs
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.viewmodel.AlbumDetailViewModel

/** The photos and videos of one album, folder or smart collection, grouped by day. */
class AlbumDetailActivity : AppCompatActivity() {

    private var binding: ActivityAlbumDetailBinding? = null
    private val viewModel: AlbumDetailViewModel by viewModels()
    private var adapter: MediaPagingAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityAlbumDetailBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.tvTitle.text = viewModel.title
        b.btnBack.setOnClickListener { finish() }
        // Adding, selecting and the overflow menu have no backing feature yet.
        val comingSoon = View.OnClickListener {
            android.widget.Toast.makeText(applicationContext, R.string.albums_coming_soon, android.widget.Toast.LENGTH_SHORT).show()
        }
        b.btnAddPhotos.setOnClickListener(comingSoon)
        b.btnSelect.setOnClickListener(comingSoon)
        b.btnMore.setOnClickListener(comingSoon)

        // Same grid as the Gallery tab: the saved column count, day headers across the full width.
        val columns = GalleryLayoutPrefs.getColumns(this)
        val mediaAdapter = MediaPagingAdapter((resources.displayMetrics.widthPixels / columns).coerceAtLeast(1))
        adapter = mediaAdapter
        val manager = GridLayoutManager(this, columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (mediaAdapter.isHeader(position)) columns else 1
        }
        b.rvMedia.layoutManager = manager
        b.rvMedia.adapter = mediaAdapter
        b.rvMedia.itemAnimator = null
        b.rvMedia.setHasFixedSize(true)

        mediaAdapter.addLoadStateListener { states ->
            val empty = states.refresh is LoadState.NotLoading && mediaAdapter.itemCount == 0
            b.emptyGroup.visibility = if (empty) View.VISIBLE else View.GONE
        }
        viewModel.media.observe(this) { data -> mediaAdapter.submitData(lifecycle, data) }
        viewModel.count.observe(this) { b.tvCount.text = getString(R.string.photo_items, it ?: 0) }
    }

    override fun onDestroy() {
        binding?.rvMedia?.adapter = null
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, kind: AlbumKind, id: Long, title: String): Intent =
            Intent(context, AlbumDetailActivity::class.java)
                .putExtra(AlbumDetailViewModel.ARG_KIND, kind.key)
                .putExtra(AlbumDetailViewModel.ARG_ID, id)
                .putExtra(AlbumDetailViewModel.ARG_TITLE, title)
    }
}
