package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.adapter.HighlightsDetailAdapter
import com.example.thirdgallery.databinding.ActivityHighlightsDetailBinding
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.HighlightDetail
import com.example.thirdgallery.model.HighlightRow
import com.example.thirdgallery.viewmodel.HighlightsDetailViewModel

/**
 * A highlight opened from For you: its hero (title, period, count and the Play button) and every media of it
 * grouped by day. Play starts the slideshow in [HighlightsStartActivity]; a tile opens the viewer inside this
 * highlight only.
 */
class HighlightsDetailActivity : AppCompatActivity() {

    private var binding: ActivityHighlightsDetailBinding? = null
    private val viewModel: HighlightsDetailViewModel by viewModels()
    private var adapter: HighlightsDetailAdapter? = null
    private var detail: HighlightDetail? = null
    private var lastOpenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Nothing usable in the intent (or opened by hand): there is no highlight to show.
        if (viewModel.card == null) {
            finish()
            return
        }

        val b = ActivityHighlightsDetailBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val list = HighlightsDetailAdapter(
            thumbSizePx = (resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1),
            onPlay = { play() },
            onOpen = { open(it) },
        )
        adapter = list
        val manager = GridLayoutManager(this, COLUMNS)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (list.isFullWidth(position)) COLUMNS else 1
        }
        b.rvItems.layoutManager = manager
        b.rvItems.adapter = list
        b.rvItems.itemAnimator = null

        b.btnBack.setOnClickListener { finish() }
        b.btnSearch.setOnClickListener { if (canOpen()) startActivity(SearchActivity.intent(this)) }

        viewModel.detail.observe(this) { result ->
            detail = result
            list.submitList(result.rows)
            val empty = result.rows.isEmpty()
            b.emptyGroup.visibility = if (empty) View.VISIBLE else View.GONE
            b.rvItems.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        // Media can be deleted or moved elsewhere while this screen was away.
        viewModel.load()
    }

    private fun play() {
        val uids = detail?.slideshow.orEmpty()
        if (uids.isEmpty() || !canOpen()) return
        startActivity(HighlightsStartActivity.intent(this, uids))
    }

    private fun open(uid: Long) {
        if (!canOpen()) return
        // The viewer swipes through this highlight's media only, in the order shown here.
        val shown = detail?.rows.orEmpty().filterIsInstance<HighlightRow.Tile>().map { it.media.uid }
        if (uid !in shown) return
        startActivity(FullScreenMediaActivity.intentForList(this, uid, shown))
    }

    /** Ignores a second tap that arrives while the first screen is still opening. */
    private fun canOpen(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return false
        lastOpenAt = now
        return true
    }

    override fun onDestroy() {
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3
        private const val OPEN_GAP_MS = 600L

        fun intent(context: Context, card: HighlightCard): Intent =
            Intent(context, HighlightsDetailActivity::class.java)
                .putExtra(HighlightsDetailViewModel.ARG_KIND, card.kind.ordinal)
                .putExtra(HighlightsDetailViewModel.ARG_FROM, card.fromMs)
                .putExtra(HighlightsDetailViewModel.ARG_TO, card.toMs)
                .putExtra(HighlightsDetailViewModel.ARG_UIDS, card.uids.toLongArray())
    }
}
