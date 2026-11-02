package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import com.example.thirdgallery.adapter.CategoryHeaderAdapter
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.databinding.ActivityCategoryMediaBinding
import com.example.thirdgallery.helper.GalleryLayoutPrefs
import com.example.thirdgallery.helper.SelectionBarController
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.viewmodel.CategoryMediaViewModel
import com.example.thirdgallery.viewmodel.SelectionViewModel

/**
 * One "Made for you" category of the For you screen (design: Best photos): back, select and search on top, the
 * category name with "N items · what it holds", then its photos grouped by day. One screen and one adapter serve
 * Best photos, Best portraits and Recently edited; the category travels as an [AlbumKind].
 */
class CategoryMediaActivity : AppCompatActivity() {

    private var binding: ActivityCategoryMediaBinding? = null
    private val viewModel: CategoryMediaViewModel by viewModels()
    private val selectionViewModel: SelectionViewModel by viewModels()
    private var lastOpenAt = 0L

    private val selectionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        selectionViewModel.onRequestResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityCategoryMediaBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { finish() }
        b.btnSearch.setOnClickListener { startActivity(SearchActivity.intent(this)) }

        // The same grid as the Gallery tab: the saved column count, day headers across the full width.
        val columns = GalleryLayoutPrefs.getColumns(this)
        val headerAdapter = CategoryHeaderAdapter(getString(viewModel.titleRes), getString(viewModel.subtitleRes))
        selectionViewModel.setSource(viewModel.kind, 0L, MediaFilter.ALL)
        var gridAdapter: MediaPagingAdapter? = null
        val bars = SelectionBarController(
            bars = b.selectionBars,
            activity = this,
            owner = this,
            viewModel = selectionViewModel,
            sourceAlbumId = { 0L },
            launch = { selectionLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) },
            onSelectionChanged = { gridAdapter?.refreshSelection() },
        )
        b.btnSelect.setOnClickListener { selectionViewModel.begin() }
        val mediaAdapter = MediaPagingAdapter((resources.displayMetrics.widthPixels / columns).coerceAtLeast(1), { openMedia(it) }, bars.selection)
        gridAdapter = mediaAdapter
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
        // An empty list says whether the library is still being checked or nothing matches.
        viewModel.checking.observe(this) { checking ->
            b.tvEmptyDesc.setText(if (checking == true) R.string.category_scanning else R.string.category_empty_desc)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    /** Opens the tapped photo in the viewer, inside this category; double taps are ignored. */
    private fun openMedia(uid: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(FullScreenMediaActivity.intent(this, uid, viewModel.kind, 0L))
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val OPEN_GAP_MS = 600L

        /** [kind] is one of [CategoryMediaViewModel.CATEGORIES]. */
        fun intent(context: Context, kind: AlbumKind): Intent =
            Intent(context, CategoryMediaActivity::class.java).putExtra(CategoryMediaViewModel.ARG_KIND, kind.key)
    }
}
