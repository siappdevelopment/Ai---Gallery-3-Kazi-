package com.photovideo.gallerykz.memories.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.SuggestionPickHeaderAdapter
import com.photovideo.gallerykz.memories.adapter.SuggestionTileAdapter
import com.photovideo.gallerykz.memories.databinding.ActivitySuggestionPickBinding
import com.photovideo.gallerykz.memories.fragment.AddToAlbumBottomSheet
import com.photovideo.gallerykz.memories.model.SuggestionKind
import com.photovideo.gallerykz.memories.viewmodel.PickEvent
import com.photovideo.gallerykz.memories.viewmodel.SuggestionPickViewModel

/**
 * "Add these to an album" and "Favorite these best shots" (design): the suggested photos in a grid, all selected at
 * first; tap to take one out. Dismiss forgets the suggestion; the main button adds the selection to an album (through the
 * existing album picker) or to Favorites. One screen and one adapter serve both suggestions.
 */
class SuggestionPickActivity : AppCompatActivity() {

    private var binding: ActivitySuggestionPickBinding? = null
    private val viewModel: SuggestionPickViewModel by viewModels()

    private val requestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onRequestResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivitySuggestionPickBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val isAlbum = viewModel.kind == SuggestionKind.ALBUM
        val title = getString(if (isAlbum) R.string.home_sug_album_title else R.string.home_sug_fav_title)
        val columns = COLUMNS
        val header = SuggestionPickHeaderAdapter(onBack = { finish() }, onToggleAll = { viewModel.toggleAll() })
        val tiles = SuggestionTileAdapter((resources.displayMetrics.widthPixels / columns).coerceAtLeast(1)) { viewModel.toggle(it) }
        val manager = GridLayoutManager(this, columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (position == 0) columns else 1
        }
        b.rvTiles.layoutManager = manager
        b.rvTiles.adapter = ConcatAdapter(header, tiles)
        b.rvTiles.itemAnimator = null

        b.btnDismiss.setOnClickListener { viewModel.dismiss() }
        b.btnAction.setOnClickListener { onAction() }
        supportFragmentManager.setFragmentResultListener(AddToAlbumBottomSheet.RESULT_KEY, this) { _, _ -> finish() }

        viewModel.items.observe(this) { items ->
            tiles.submitList(items)
            // Nothing left of the suggestion (everything was deleted or favorited meanwhile): close the screen.
            if (viewModel.loaded.value == true && items.isEmpty()) finish()
        }
        viewModel.loaded.observe(this) { loaded ->
            if (loaded == true && viewModel.items.value.orEmpty().isEmpty()) finish()
        }
        viewModel.selected.observe(this) { selected ->
            val total = viewModel.items.value.orEmpty().size
            tiles.setSelected(selected)
            val description = if (isAlbum) {
                val range = DateUtils.formatDateRange(
                    this, viewModel.fromMs, viewModel.toMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
                )
                getString(R.string.suggest_album_desc, total, range)
            } else {
                getString(R.string.suggest_fav_desc)
            }
            header.update(title, description, selected.size, total > 0 && selected.size == total)
            b.btnAction.text = getString(if (isAlbum) R.string.suggest_cta_album else R.string.suggest_cta_fav, selected.size)
            b.btnAction.isEnabled = selected.isNotEmpty()
            b.btnAction.alpha = if (selected.isNotEmpty()) 1f else 0.5f
        }
        viewModel.events.observe(this) { event ->
            when (val content = event.getContentIfNotHandled()) {
                is PickEvent.Launch -> requestLauncher.launch(IntentSenderRequest.Builder(content.intent.intentSender).build())
                is PickEvent.Message -> toast(content.res, content.count)
                is PickEvent.Done -> {
                    if (content.message != 0) toast(content.message, content.count)
                    finish()
                }
                null -> Unit
            }
        }
    }

    private fun onAction() {
        val selected = viewModel.selectedList()
        if (selected.isEmpty()) return
        if (viewModel.kind == SuggestionKind.ALBUM) {
            // The existing album picker: pick an album (or create one) and every selected photo goes into it.
            val fm = supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(AddToAlbumBottomSheet.TAG) != null) return
            AddToAlbumBottomSheet.newForSelection(selected, false, 0L).show(fm, AddToAlbumBottomSheet.TAG)
        } else {
            viewModel.addToFavorites()
        }
    }

    private fun toast(res: Int, count: Int) {
        val text = if (count > 0) getString(res, count) else getString(res)
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3

        /** [kind] is [SuggestionKind.ALBUM] or [SuggestionKind.FAVORITES]; [fromMs]..[toMs] is the period of an album suggestion. */
        fun intent(context: Context, kind: SuggestionKind, uids: List<Long>, fromMs: Long, toMs: Long): Intent =
            Intent(context, SuggestionPickActivity::class.java)
                .putExtra(SuggestionPickViewModel.ARG_KIND, kind.ordinal)
                .putExtra(SuggestionPickViewModel.ARG_UIDS, uids.toLongArray())
                .putExtra(SuggestionPickViewModel.ARG_FROM, fromMs)
                .putExtra(SuggestionPickViewModel.ARG_TO, toMs)
                .putExtra(SuggestionPickViewModel.ARG_KEY, uids.lastOrNull() ?: 0L)
    }
}
