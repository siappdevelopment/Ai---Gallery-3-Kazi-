package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.databinding.ItemVaultPageBinding
import com.example.thirdgallery.helper.VaultFiles

/**
 * One full-screen page per vault item. A photo is shown at once; a video shows its first frame with a play
 * button that starts it inside the page (the file is private, so no other app is handed it).
 */
class VaultPageAdapter : ListAdapter<VaultItemEntity, VaultPageAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemVaultPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.release()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemVaultPageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VaultItemEntity) {
            release()
            val file = VaultFiles.file(binding.root.context, item.fileName)
            Glide.with(binding.ivPhoto).load(file).dontAnimate().into(binding.ivPhoto)
            val isVideo = item.mediaType == MediaTypes.VIDEO
            binding.btnPlay.visibility = if (isVideo) View.VISIBLE else View.GONE
            binding.btnPlay.setOnClickListener { play(file.absolutePath) }
        }

        private fun play(path: String) {
            binding.btnPlay.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            val controller = MediaController(binding.root.context)
            controller.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(controller)
            binding.videoView.setVideoPath(path)
            binding.videoView.setOnPreparedListener {
                binding.ivPhoto.visibility = View.INVISIBLE
                it.start()
            }
            binding.videoView.setOnErrorListener { _, _, _ ->
                release()
                true
            }
        }

        /** Stops a running video and shows the still again (the page was recycled or rebound). */
        fun release() {
            binding.videoView.stopPlayback()
            binding.videoView.visibility = View.GONE
            binding.ivPhoto.visibility = View.VISIBLE
            if (!isHostDestroyed(binding.root.context)) Glide.with(binding.ivPhoto).clear(binding.ivPhoto)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VaultItemEntity>() {
            override fun areItemsTheSame(old: VaultItemEntity, new: VaultItemEntity) = old.id == new.id
            override fun areContentsTheSame(old: VaultItemEntity, new: VaultItemEntity) = old == new
        }

        fun isHostDestroyed(context: Context): Boolean {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return current.isDestroyed
                current = current.baseContext
            }
            return false
        }
    }
}
