package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemVaultFolderBinding
import com.example.thirdgallery.databinding.ItemVaultTileBinding
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.VaultFiles
import com.example.thirdgallery.model.VaultFolderUi

/** The two-column folder cards of the vault home (All items, Photos, Videos and the default folders). */
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
            binding.tvName.setText(folder.name)
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

/** The square "Recently added" tiles of the vault home, with the length on videos. */
class VaultTileAdapter(
    private val thumbSizePx: Int,
    private val onOpen: (VaultItemEntity) -> Unit,
) : ListAdapter<VaultItemEntity, VaultTileAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemVaultTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), thumbSizePx, onOpen)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemVaultTileBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VaultItemEntity, sizePx: Int, onOpen: (VaultItemEntity) -> Unit) {
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
            binding.root.setOnClickListener { onOpen(item) }
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

// The grid is detached while the activity is destroyed; Glide.with() throws then.
private fun isHostDestroyed(context: Context): Boolean {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current.isDestroyed
        current = current.baseContext
    }
    return false
}
