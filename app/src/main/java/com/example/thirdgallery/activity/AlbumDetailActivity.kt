package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.AlbumDetailHeaderAdapter
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.databinding.ActivityAlbumDetailBinding
import com.example.thirdgallery.helper.GalleryLayoutPrefs
import com.example.thirdgallery.helper.SelectionBarController
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.viewmodel.SelectionViewModel
import com.example.thirdgallery.viewmodel.AlbumDetailViewModel

/** The photos and videos of one album, folder or smart collection, grouped by day. */
class AlbumDetailActivity : AppCompatActivity() {

    private var binding: ActivityAlbumDetailBinding? = null
    private val viewModel: AlbumDetailViewModel by viewModels()
    private var adapter: MediaPagingAdapter? = null
    private var lastOpenAt = 0L
    private val selectionViewModel: SelectionViewModel by viewModels()

    private val selectionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        selectionViewModel.onRequestResult(result.resultCode == RESULT_OK)
    }

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

        b.btnBack.setOnClickListener { finish() }
        // Adding, selecting and the overflow menu have no backing feature yet.
        val comingSoon = View.OnClickListener {
            android.widget.Toast.makeText(applicationContext, R.string.albums_coming_soon, android.widget.Toast.LENGTH_SHORT).show()
        }
        b.btnAddPhotos.visibility = if (viewModel.isRegularAlbum) View.VISIBLE else View.GONE
        b.btnAddPhotos.setOnClickListener(comingSoon)
        b.btnMore.setOnClickListener(comingSoon)

        // Same grid as the Gallery tab: the saved column count, day headers across the full width.
        val columns = GalleryLayoutPrefs.getColumns(this)
        val headerAdapter = AlbumDetailHeaderAdapter(viewModel.title)
        selectionViewModel.setSource(viewModel.sourceKind, viewModel.sourceId, MediaFilter.ALL)
        var gridAdapter: MediaPagingAdapter? = null
        val bars = SelectionBarController(
            bars = b.selectionBars,
            activity = this,
            owner = this,
            viewModel = selectionViewModel,
            // Move takes the items out of this album when it is an app album.
            sourceAlbumId = { if (viewModel.sourceKind == AlbumKind.USER) viewModel.sourceId else 0L },
            launch = { selectionLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) },
            onSelectionChanged = { gridAdapter?.refreshSelection() },
        )
        b.btnSelect.setOnClickListener { selectionViewModel.begin() }
        val mediaAdapter = MediaPagingAdapter((resources.displayMetrics.widthPixels / columns).coerceAtLeast(1), { openMedia(it) }, bars.selection)
        gridAdapter = mediaAdapter
        adapter = mediaAdapter
        val manager = GridLayoutManager(this, columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                // row 0 is the title row, the media rows follow it
                if (position == 0 || mediaAdapter.isHeader(position - 1)) columns else 1
        }
        b.rvMedia.layoutManager = manager
        b.rvMedia.adapter = ConcatAdapter(headerAdapter, mediaAdapter)
        b.rvMedia.itemAnimator = null
        b.rvMedia.setHasFixedSize(true)

        mediaAdapter.addLoadStateListener { states ->
            val empty = states.refresh is LoadState.NotLoading && mediaAdapter.itemCount == 0
            b.emptyGroup.visibility = if (empty) View.VISIBLE else View.GONE
        }
        viewModel.media.observe(this) { data -> mediaAdapter.submitData(lifecycle, data) }
        viewModel.count.observe(this) { headerAdapter.setCount(it ?: 0) }
    }

    /** Opens the tapped item in the photo viewer, inside this album; double taps are ignored. */
    private fun openMedia(uid: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(FullScreenMediaActivity.intent(this, uid, viewModel.sourceKind, viewModel.sourceId))
    }

    override fun onDestroy() {
        binding?.rvMedia?.adapter = null
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val OPEN_GAP_MS = 600L

        fun intent(context: Context, kind: AlbumKind, id: Long, title: String): Intent =
            Intent(context, AlbumDetailActivity::class.java)
                .putExtra(AlbumDetailViewModel.ARG_KIND, kind.key)
                .putExtra(AlbumDetailViewModel.ARG_ID, id)
                .putExtra(AlbumDetailViewModel.ARG_TITLE, title)
    }
}
