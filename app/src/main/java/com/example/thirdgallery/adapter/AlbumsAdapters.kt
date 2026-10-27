package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemAlbumCardBinding
import com.example.thirdgallery.databinding.ItemAlbumsHeaderBinding
import com.example.thirdgallery.databinding.ItemAlbumsSmartBinding
import com.example.thirdgallery.databinding.ItemSmartRowBinding
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.AlbumCard
import com.example.thirdgallery.model.AlbumKind

private fun loadCover(view: ImageView, uid: Long?, widthPx: Int, heightPx: Int) {
    if (uid == null) {
        Glide.with(view).clear(view)
        view.setImageDrawable(null)
        return
    }
    Glide.with(view).load(MediaUris.ofUid(uid)).override(widthPx, heightPx).centerCrop().dontAnimate().into(view)
}

/**
 * Single full-width row above the album cards: title and album count, sort / New buttons, the
 * album search field and the "Recent albums" heading. The search text lives in the ViewModel, and
 * the field itself is never re-set while typing (so the keyboard and cursor stay put).
 */
class AlbumsHeaderAdapter(
    private val onSort: () -> Unit,
    private val onNew: () -> Unit,
    private val onSearch: (String) -> Unit,
    private val onToggleAll: () -> Unit,
) : RecyclerView.Adapter<AlbumsHeaderAdapter.ViewHolder>() {

    private var totalAlbums = 0
    private var query = ""
    private var showAll = false
    private var canToggleAll = false
    private var noMatch = false
    private var anyAlbums = false

    fun update(totalAlbums: Int, query: String, showAll: Boolean, canToggleAll: Boolean, noMatch: Boolean, anyAlbums: Boolean) {
        this.totalAlbums = totalAlbums
        this.query = query
        this.showAll = showAll
        this.canToggleAll = canToggleAll
        this.noMatch = noMatch
        this.anyAlbums = anyAlbums
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAlbumsHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(totalAlbums, query, showAll, canToggleAll, noMatch, anyAlbums)

    inner class ViewHolder(private val binding: ItemAlbumsHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        private var binding_ = false

        init {
            binding.btnSort.setOnClickListener { onSort() }
            binding.btnNew.setOnClickListener { onNew() }
            binding.tvToggleAll.setOnClickListener { onToggleAll() }
            // Search already filters while typing, so Search/Enter only closes the keyboard. Consuming it
            // stops TextView from running a focus search that can land on a view that cannot take focus.
            binding.etSearch.setOnEditorActionListener { v, actionId, event ->
                val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || enter) {
                    v.clearFocus()
                    v.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else {
                    false
                }
            }
            binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!binding_) onSearch(s?.toString().orEmpty())
                }
            })
        }

        fun bind(total: Int, query: String, showAll: Boolean, canToggle: Boolean, noMatch: Boolean, anyAlbums: Boolean) {
            val resources = binding.root.resources
            binding.tvAlbumCount.text = resources.getQuantityString(R.plurals.albums_count, total, total)
            binding.tvToggleAll.visibility = if (canToggle) View.VISIBLE else View.GONE
            binding.tvToggleAll.setText(if (showAll) R.string.albums_show_less else R.string.albums_view_all)
            binding.tvNoMatch.visibility = if (noMatch || !anyAlbums) View.VISIBLE else View.GONE
            binding.tvNoMatch.setText(if (anyAlbums) R.string.albums_no_match else R.string.albums_none)

            // Only write the text when it really differs (e.g. after a restore), never while typing.
            if (binding.etSearch.text?.toString() != query) {
                binding_ = true
                binding.etSearch.setText(query)
                binding.etSearch.setSelection(query.length)
                binding_ = false
            }
        }
    }
}

/** Album cards: one big thumbnail, three small ones, the name and the item count. */
class AlbumCardAdapter(
    private val onOpen: (AlbumCard) -> Unit,
) : ListAdapter<AlbumCard, AlbumCardAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAlbumCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemAlbumCardBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onOpen(getItem(position))
            }
        }

        fun bind(card: AlbumCard) {
            val density = binding.root.resources.displayMetrics.density
            val big = (180 * density).toInt()
            val small = (80 * density).toInt()
            loadCover(binding.ivCover, card.covers.getOrNull(0), big, (116 * density).toInt())
            loadCover(binding.ivThumb1, card.covers.getOrNull(1), small, (36 * density).toInt())
            loadCover(binding.ivThumb2, card.covers.getOrNull(2), small, (36 * density).toInt())
            loadCover(binding.ivThumb3, card.covers.getOrNull(3), small, (36 * density).toInt())
            binding.tvName.text = card.name
            binding.tvMeta.text = binding.root.resources.getString(R.string.photo_items, card.total)
        }

        fun clear() {
            Glide.with(binding.ivCover).clear(binding.ivCover)
            Glide.with(binding.ivThumb1).clear(binding.ivThumb1)
            Glide.with(binding.ivThumb2).clear(binding.ivThumb2)
            Glide.with(binding.ivThumb3).clear(binding.ivThumb3)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AlbumCard>() {
            override fun areItemsTheSame(old: AlbumCard, new: AlbumCard) = old.key == new.key
            override fun areContentsTheSame(old: AlbumCard, new: AlbumCard) = old == new
        }
    }
}

/**
 * "Smart collections": Favorites, Videos, Screenshots and Recently added open with live counts. Photo vault
 * and Bin have no storage yet, so they are shown as in the design and only call [onUnavailable].
 */
class SmartCollectionsAdapter(
    private val onOpen: (AlbumKind, String) -> Unit,
    private val onUnavailable: () -> Unit,
    private val onOpenBin: () -> Unit,
) : RecyclerView.Adapter<SmartCollectionsAdapter.ViewHolder>() {

    private var counts = IntArray(4)

    fun setCounts(favorites: Int, videos: Int, screenshots: Int, recent: Int) {
        val next = intArrayOf(favorites, videos, screenshots, recent)
        if (next.contentEquals(counts)) return
        counts = next
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemAlbumsSmartBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(counts)

    inner class ViewHolder(private val binding: ItemAlbumsSmartBinding) : RecyclerView.ViewHolder(binding.root) {

        private val rows = listOf(
            Row(binding.rowFavorites, AlbumKind.FAVORITES, R.drawable.ic_favorite, R.string.photo_chip_favorites),
            Row(binding.rowVideos, AlbumKind.VIDEOS, R.drawable.ic_smart_display, R.string.photo_chip_videos),
            Row(binding.rowScreenshots, AlbumKind.SCREENSHOTS, R.drawable.ic_album_screenshot, R.string.albums_smart_screenshots),
            Row(binding.rowRecent, AlbumKind.RECENT, R.drawable.ic_schedule, R.string.albums_smart_recent),
        )

        private val unavailableRows = listOf(
            Triple(binding.rowVault, R.drawable.ic_vault, R.string.albums_smart_vault),
            Triple(binding.rowBin, R.drawable.ic_delete, R.string.albums_smart_bin),
        )

        init {
            rows.forEach { row ->
                row.binding.ivIcon.setImageResource(row.icon)
                row.binding.tvLabel.setText(row.label)
                row.binding.root.setOnClickListener {
                    onOpen(row.kind, row.binding.root.resources.getString(row.label))
                }
            }
            unavailableRows.forEach { (row, icon, label) ->
                row.ivIcon.setImageResource(icon)
                row.tvLabel.setText(label)
                row.root.setOnClickListener { onUnavailable() }
            }
            binding.rowBin.root.setOnClickListener { onOpenBin() }
            binding.rowVault.tvCount.setText(R.string.albums_vault_setup)
            binding.rowBin.tvCount.visibility = View.GONE
        }

        fun bind(counts: IntArray) {
            rows.forEachIndexed { index, row -> row.binding.tvCount.text = counts[index].toString() }
        }
    }

    private class Row(val binding: ItemSmartRowBinding, val kind: AlbumKind, val icon: Int, val label: Int)
}
