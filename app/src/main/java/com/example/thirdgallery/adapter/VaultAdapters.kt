package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.databinding.ItemMediaBinding
import com.example.thirdgallery.databinding.ItemVaultFolderBinding
import com.example.thirdgallery.databinding.ItemVaultTileBinding
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.helper.VaultFiles
import com.example.thirdgallery.model.VaultFolderUi

/** The two-column folder cards of the vault home (All items, Photos, Videos and the user's folders). */
class VaultFolderAdapter(
    private val onOpen: (VaultFolderUi) -> Unit,
) : ListAdapter<VaultFolderUi, VaultFolderAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemVaultFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), onOpen)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemVaultFolderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: VaultFolderUi, onOpen: (VaultFolderUi) -> Unit) {
            val res = binding.root.resources
            binding.ivIcon.setImageResource(folder.icon)
            binding.tvName.text = folder.label(binding.root.context)
            binding.tvCount.text = res.getQuantityString(R.plurals.vault_folder_items, folder.count, folder.count)
            binding.root.setOnClickListener { onOpen(folder) }
            val cover = folder.coverFile
            if (cover == null) {
                clear()
                binding.ivCover.setImageResource(R.color.vault_folder_cover)
            } else {
                Glide.with(binding.ivCover)
                    .load(VaultFiles.file(binding.root.context, cover))
                    .centerCrop()
                    .dontAnimate()
                    .placeholder(R.color.vault_folder_cover)
                    .into(binding.ivCover)
            }
        }

        fun clear() {
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivCover).clear(binding.ivCover)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VaultFolderUi>() {
            override fun areItemsTheSame(old: VaultFolderUi, new: VaultFolderUi) = old.key == new.key
            override fun areContentsTheSame(old: VaultFolderUi, new: VaultFolderUi) = old == new
        }
    }
}

/** What a vault grid needs to know about its multi-selection (ids are [VaultItemEntity.id]). */
interface VaultSelection {
    val active: Boolean
    fun isSelected(id: Long): Boolean

    companion object {
        /** No selection (the vault home's "Recently added"). */
        val NONE = object : VaultSelection {
            override val active = false
            override fun isSelected(id: Long) = false
        }
    }
}

/** The square tiles of the vault (recently added, folder screens), with the length on videos and the selection marks. */
class VaultTileAdapter(
    private val thumbSizePx: Int,
    private val selection: VaultSelection = VaultSelection.NONE,
    private val onToggle: (VaultItemEntity) -> Unit = {},
    private val onOpen: (VaultItemEntity) -> Unit,
) : ListAdapter<VaultItemEntity, VaultTileAdapter.ViewHolder>(DIFF) {

    /** Selection changed: redraw only the check marks. */
    fun refreshSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemVaultTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), thumbSizePx, selection, onOpen, onToggle)

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position), selection)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemVaultTileBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: VaultItemEntity,
            sizePx: Int,
            selection: VaultSelection,
            onOpen: (VaultItemEntity) -> Unit,
            onToggle: (VaultItemEntity) -> Unit,
        ) {
            Glide.with(binding.ivThumb)
                .load(VaultFiles.file(binding.root.context, item.fileName))
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(binding.ivThumb)
            val isVideo = item.mediaType == MediaTypes.VIDEO
            binding.tvDuration.visibility = if (isVideo) View.VISIBLE else View.GONE
            if (isVideo) binding.tvDuration.text = MediaFormat.duration(item.durationMs)
            binding.root.setOnClickListener { if (selection.active) onToggle(item) else onOpen(item) }
            bindSelection(item, selection)
        }

        fun bindSelection(item: VaultItemEntity, selection: VaultSelection) {
            drawSelection(binding.ivCheck, binding.ivThumb, selection.active, selection.active && selection.isSelected(item.id))
        }

        fun clear() {
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivThumb).clear(binding.ivThumb)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VaultItemEntity>() {
            override fun areItemsTheSame(old: VaultItemEntity, new: VaultItemEntity) = old.id == new.id
            override fun areContentsTheSame(old: VaultItemEntity, new: VaultItemEntity) = old == new
        }
    }
}

/** Gallery tiles of the "Add to vault" picker: every tile carries a check circle, chosen ones are shrunk. */
class VaultPickAdapter(
    private val thumbSizePx: Int,
    private val isSelected: (Long) -> Boolean,
    private val onToggle: (Long) -> Unit,
) : PagingDataAdapter<MediaEntity, VaultPickAdapter.ViewHolder>(DIFF) {

    /** Selection changed: redraw only the check marks. */
    fun refreshSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it, thumbSizePx, isSelected, onToggle) }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            getItem(position)?.let { holder.bindSelection(it.uid, isSelected) }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(media: MediaEntity, sizePx: Int, isSelected: (Long) -> Boolean, onToggle: (Long) -> Unit) {
            Glide.with(binding.ivThumb)
                .load(MediaUris.of(media.mediaId, media.mediaType))
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(binding.ivThumb)
            val isVideo = media.mediaType == MediaTypes.VIDEO
            binding.tvDuration.visibility = if (isVideo) View.VISIBLE else View.GONE
            if (isVideo) binding.tvDuration.text = MediaFormat.duration(media.durationMs)
            binding.ivFavorite.visibility = View.GONE
            binding.root.setOnClickListener { onToggle(media.uid) }
            bindSelection(media.uid, isSelected)
        }

        fun bindSelection(uid: Long, isSelected: (Long) -> Boolean) {
            drawSelection(binding.ivCheck, binding.ivThumb, true, isSelected(uid))
        }

        fun clear() {
            if (isHostDestroyed(binding.root.context)) return
            Glide.with(binding.ivThumb).clear(binding.ivThumb)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MediaEntity>() {
            override fun areItemsTheSame(old: MediaEntity, new: MediaEntity) = old.uid == new.uid
            override fun areContentsTheSame(old: MediaEntity, new: MediaEntity) = old == new
        }
    }
}

// The grid is detached while the activity is destroyed; Glide.with() throws then.
private fun isHostDestroyed(context: Context): Boolean {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current.isDestroyed
        current = current.baseContext
    }
    return false
}
