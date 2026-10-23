package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.adapter.SearchHeaderAdapter
import com.example.thirdgallery.databinding.ActivitySearchBinding
import com.example.thirdgallery.databinding.ItemSearchRecentBinding
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.viewmodel.SearchViewModel

/**
 * Search (design 19): type a file name and the matching photos and videos appear as you type. With an
 * empty query the recent searches, People and Browse are shown. A tapped result opens in the viewer,
 * swipeable through all the results of that search.
 */
class SearchActivity : AppCompatActivity() {

    private var binding: ActivitySearchBinding? = null
    private val viewModel: SearchViewModel by viewModels()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingQuery: Runnable? = null
    private var lastOpenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivitySearchBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { finish() }
        b.btnClear.setOnClickListener { b.etSearch.setText("") }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onTextEdited(s?.toString().orEmpty())
        })
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }

        val columns = COLUMNS
        val header = SearchHeaderAdapter()
        val results = MediaPagingAdapter((resources.displayMetrics.widthPixels / columns).coerceAtLeast(1), { openResult(it) })
        val manager = GridLayoutManager(this, columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (position == 0) columns else 1
        }
        b.rvResults.layoutManager = manager
        b.rvResults.adapter = ConcatAdapter(header, results)
        b.rvResults.itemAnimator = null
        b.rvResults.setHasFixedSize(true)

        results.addLoadStateListener { states ->
            val query = viewModel.query.value.orEmpty()
            val empty = states.refresh is LoadState.NotLoading && results.itemCount == 0
            b.noResultsGroup.visibility = if (query.isNotEmpty() && empty) View.VISIBLE else View.GONE
            b.rvResults.visibility = if (query.isNotEmpty() && !empty) View.VISIBLE else View.GONE
        }
        viewModel.results.observe(this) { data -> results.submitData(lifecycle, data) }
        viewModel.count.observe(this) { header.setCount(it ?: 0) }
        viewModel.query.observe(this) { renderQuery(it.orEmpty()) }
        viewModel.history.observe(this) { renderHistory(it.orEmpty()) }

        // Start with the query that was typed before a rotation; otherwise show the keyboard.
        val restored = viewModel.query.value.orEmpty()
        if (restored.isNotEmpty() && b.etSearch.text.isNullOrEmpty()) {
            b.etSearch.setText(restored)
            b.etSearch.setSelection(restored.length)
        }
        if (savedInstanceState == null) {
            b.etSearch.requestFocus()
            b.etSearch.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(b.etSearch, 0) }
        }

        val comingSoon = View.OnClickListener {
            Toast.makeText(applicationContext, R.string.albums_coming_soon, Toast.LENGTH_SHORT).show()
        }
        // People is static for now (its features come later); Browse opens the smart collections that exist.
        b.personEmma.setOnClickListener(comingSoon)
        b.personLiam.setOnClickListener(comingSoon)
        b.personOlivia.setOnClickListener(comingSoon)
        b.personNoah.setOnClickListener(comingSoon)
        b.browsePhotos.setOnClickListener(comingSoon)
        b.browseDocuments.setOnClickListener(comingSoon)
        b.browseVideos.setOnClickListener { openCollection(AlbumKind.VIDEOS, R.string.photo_chip_videos) }
        b.browseScreenshots.setOnClickListener { openCollection(AlbumKind.SCREENSHOTS, R.string.albums_smart_screenshots) }
    }

    override fun onDestroy() {
        pendingQuery?.let { handler.removeCallbacks(it) }
        pendingQuery = null
        binding = null
        super.onDestroy()
    }

    /** Results follow the typing after a short pause, so every keystroke does not start a new query. */
    private fun onTextEdited(text: String) {
        binding?.btnClear?.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        pendingQuery?.let { handler.removeCallbacks(it) }
        if (text.isBlank()) {
            // Clearing the field goes back to the recent searches at once.
            viewModel.setQuery("")
            return
        }
        val task = Runnable { viewModel.setQuery(text) }
        pendingQuery = task
        handler.postDelayed(task, DEBOUNCE_MS)
    }

    private fun renderQuery(query: String) {
        val b = binding ?: return
        val idle = query.isEmpty()
        b.idleScroll.visibility = if (idle) View.VISIBLE else View.GONE
        if (idle) {
            b.noResultsGroup.visibility = View.GONE
            b.rvResults.visibility = View.GONE
        }
    }

    private fun renderHistory(items: List<String>) {
        val b = binding ?: return
        b.recentSection.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        b.chipsRecent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEach { text ->
            val chip = ItemSearchRecentBinding.inflate(inflater, b.chipsRecent, false)
            chip.root.text = text
            chip.root.setOnClickListener { searchFor(text) }
            b.chipsRecent.addView(chip.root)
        }
    }

    /** A recent search was tapped: put it in the field and search it again. */
    private fun searchFor(text: String) {
        val b = binding ?: return
        b.etSearch.setText(text)
        b.etSearch.setSelection(text.length)
        pendingQuery?.let { handler.removeCallbacks(it) }
        viewModel.setQuery(text)
        viewModel.remember(text)
        hideKeyboard()
    }

    private fun submit() {
        val text = binding?.etSearch?.text?.toString().orEmpty()
        pendingQuery?.let { handler.removeCallbacks(it) }
        viewModel.setQuery(text)
        viewModel.remember(text)
        hideKeyboard()
    }

    private fun openResult(uid: Long) {
        val query = viewModel.query.value.orEmpty()
        if (query.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        viewModel.remember(query)
        startActivity(FullScreenMediaActivity.intent(this, uid, AlbumKind.SEARCH, 0L, query = query))
    }

    private fun openCollection(kind: AlbumKind, @androidx.annotation.StringRes title: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(AlbumDetailActivity.intent(this, kind, 0L, getString(title)))
    }

    private fun hideKeyboard() {
        val b = binding ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.etSearch.windowToken, 0)
    }

    companion object {
        private const val COLUMNS = 3
        private const val DEBOUNCE_MS = 250L
        private const val OPEN_GAP_MS = 600L

        fun intent(context: Context): Intent = Intent(context, SearchActivity::class.java)
    }
}
