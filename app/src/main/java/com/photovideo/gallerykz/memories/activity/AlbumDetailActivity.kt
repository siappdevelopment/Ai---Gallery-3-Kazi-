package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import androidx.activity.OnBackPressedCallback
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
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.AlbumDetailHeaderAdapter
import com.photovideo.gallerykz.memories.adapter.MediaPagingAdapter
import com.photovideo.gallerykz.memories.databinding.ActivityAlbumDetailBinding
import com.photovideo.gallerykz.memories.helper.GalleryLayoutPrefs
import com.photovideo.gallerykz.memories.helper.SelectionBarController
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.MediaFilter
import com.photovideo.gallerykz.memories.viewmodel.SelectionViewModel
import com.photovideo.gallerykz.memories.viewmodel.AlbumDetailViewModel

/** The photos and videos of one album, folder or smart collection, grouped by day; the Search button narrows them by file name. */
class AlbumDetailActivity : AdAwareActivity() {

    private var binding: ActivityAlbumDetailBinding? = null
    private val viewModel: AlbumDetailViewModel by viewModels()
    private var adapter: MediaPagingAdapter? = null
    private var lastOpenAt = 0L
    private val selectionViewModel: SelectionViewModel by viewModels()

    private val selectionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        selectionViewModel.onRequestResult(result.resultCode == RESULT_OK)
    }

    /** The vault setup opened by Hide when the vault has no PIN yet; a finished setup continues with Hide. */
    private val vaultSetupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        selectionViewModel.onVaultSetupResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityAlbumDetailBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.ALBUM_DETAIL)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // While the search is open Back closes it first.
            override fun handleOnBackPressed() = goBack()
        })
        setupSearch(b)

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
            launchVaultSetup = { vaultSetupLauncher.launch(it) },
            onSelectionChanged = { binding?.rvMedia?.let { rv -> gridAdapter?.refreshSelection(rv) } },
        )
        b.btnSelect.setOnClickListener { selectionViewModel.begin() }
        // An app album can always be filled again, also after all its photos were removed; folders and collections cannot.
        val canAdd = viewModel.sourceKind == AlbumKind.USER
        b.btnAddPhotos.visibility = if (canAdd) View.VISIBLE else View.GONE
        val addPhotos = { startActivity(AlbumPickActivity.intent(this, viewModel.sourceId, viewModel.title)) }
        b.btnAddPhotos.setOnClickListener { addPhotos() }
        b.btnEmptyAdd.setOnClickListener { addPhotos() }
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
        // An empty list says "no results" while searching, otherwise the album is simply empty.
        viewModel.query.observe(this) { q ->
            val searching = q.isNotEmpty()
            b.tvEmptyTitle.setText(if (searching) R.string.search_no_results else R.string.photo_filter_empty_title)
            b.tvEmptyDesc.setText(if (searching) R.string.search_no_results_desc else R.string.photo_filter_empty_desc)
            // The empty album offers "Add photos" right where it says there is nothing.
            b.btnEmptyAdd.visibility = if (!searching && viewModel.sourceKind == AlbumKind.USER) View.VISIBLE else View.GONE
        }
        viewModel.media.observe(this) { data -> mediaAdapter.submitData(lifecycle, data) }
        viewModel.count.observe(this) { headerAdapter.setCount(it ?: 0) }
    }

    /** The search field: opens from the Search button, narrows this album by file name as the user types. */
    private fun setupSearch(b: ActivityAlbumDetailBinding) {
        b.btnSearch.setOnClickListener { showSearch(b, true) }
        b.btnClear.setOnClickListener { b.etSearch.setText("") }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                b.btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                viewModel.setQuery(s?.toString().orEmpty())
            }
        })
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) hideKeyboard() else return@setOnEditorActionListener false
            true
        }
        // After a rotation the field comes back open with the same text.
        if (viewModel.searchOpen) {
            val restored = viewModel.query.value.orEmpty()
            b.etSearch.setText(restored)
            b.etSearch.setSelection(restored.length)
            showSearch(b, true, false)
        }
    }

    private fun showSearch(b: ActivityAlbumDetailBinding, open: Boolean, focus: Boolean = true) {
        viewModel.searchOpen = open
        b.searchBox.visibility = if (open) View.VISIBLE else View.GONE
        b.topSpacer.visibility = if (open) View.GONE else View.VISIBLE
        // Selecting works on the whole album, so it waits until the search is closed.
        b.btnSelect.visibility = if (open) View.GONE else View.VISIBLE
        b.btnSearch.visibility = if (open) View.GONE else View.VISIBLE
        if (viewModel.sourceKind == AlbumKind.USER) b.btnAddPhotos.visibility = if (open) View.GONE else View.VISIBLE
        if (open) {
            if (focus) {
                b.etSearch.requestFocus()
                b.etSearch.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(b.etSearch, 0) }
            }
        } else {
            b.etSearch.setText("")
            hideKeyboard()
        }
    }

    /** Back closes the search first, then leaves the album. */
    private fun goBack() {
        val b = binding
        if (b != null && viewModel.searchOpen) showSearch(b, false) else finish()
    }

    private fun hideKeyboard() {
        val b = binding ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.etSearch.windowToken, 0)
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
