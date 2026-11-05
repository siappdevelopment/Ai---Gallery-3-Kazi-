package com.photovideo.gallerykz.memories.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.VaultPickAdapter
import com.photovideo.gallerykz.memories.databinding.ActivityAlbumPickBinding
import com.photovideo.gallerykz.memories.viewmodel.AlbumPickViewModel

/**
 * "Add photos" for an existing app album: the gallery with check marks, "Select all" and an Add button. Reached from the
 * album's own screen, so an album that was emptied (or never filled) can always be filled again.
 */
class AlbumPickActivity : AppCompatActivity() {

    private var binding: ActivityAlbumPickBinding? = null
    private val viewModel: AlbumPickViewModel by viewModels()
    private var adapter: VaultPickAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityAlbumPickBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (viewModel.albumId <= 0L) {
            finish()
            return
        }

        b.tvDesc.text = getString(R.string.album_pick_desc, viewModel.albumTitle)

        val pick = VaultPickAdapter(
            thumbSizePx = (resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1),
            isSelected = { viewModel.isSelected(it) },
            onToggle = { viewModel.toggle(it) },
        )
        adapter = pick
        b.rvMedia.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvMedia.adapter = pick
        b.rvMedia.itemAnimator = null

        b.btnBack.setOnClickListener { finish() }
        b.btnSelectAll.setOnClickListener { viewModel.toggleAll() }
        b.btnAdd.setOnClickListener { viewModel.add() }

        viewModel.items.observe(this) { pick.submitData(lifecycle, it) }
        viewModel.total.observe(this) { b.tvEmpty.visibility = if (it == 0) View.VISIBLE else View.GONE }
        viewModel.count.observe(this) { count ->
            val total = viewModel.total.value ?: 0
            b.tvSelected.text = getString(R.string.selection_selected, count)
            b.btnSelectAll.setText(
                if (count > 0 && count >= total) R.string.vault_pick_deselect_all else R.string.selection_select_all,
            )
            b.tvAdd.text = if (count > 0) getString(R.string.album_pick_add, count) else getString(R.string.vault_pick_select_items)
            b.btnAdd.alpha = if (count > 0) 1f else 0.5f
            pick.refreshSelection()
        }
        viewModel.busy.observe(this) { busy -> b.btnAdd.isEnabled = !busy }
        viewModel.result.observe(this) { event ->
            event.getContentIfNotHandled()?.let { added ->
                if (added < 0) {
                    Toast.makeText(applicationContext, R.string.photo_add_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, getString(R.string.album_pick_added, added), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3

        fun intent(context: Context, albumId: Long, title: String): Intent =
            Intent(context, AlbumPickActivity::class.java)
                .putExtras(bundleOf(AlbumPickViewModel.ARG_ID to albumId, AlbumPickViewModel.ARG_TITLE to title))
    }
}
