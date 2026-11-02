package com.example.thirdgallery.callend

import android.content.ContentUris
import android.graphics.Outline
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.databinding.ItemCallEndMediaBinding
import com.example.thirdgallery.helper.MediaFormat
import com.example.thirdgallery.helper.clearImage
import com.example.thirdgallery.model.MediaUiModel

/** The "All" tab's grid: a day title (full width) and rounded square tiles, in the order the paged media arrives. */
class CallEndMediaAdapter(
    private val thumbPx: Int,
    private val onOpen: (Long) -> Unit,
) : PagingDataAdapter<MediaUiModel, RecyclerView.ViewHolder>(Diff) {

    fun isHeader(position: Int): Boolean = peek(position) is MediaUiModel.DayHeader

    override fun getItemViewType(position: Int): Int = if (peek(position) is MediaUiModel.DayHeader) TYPE_DAY else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY) {
            DayHolder(inflater.inflate(R.layout.item_call_end_day, parent, false) as TextView)
        } else {
            TileHolder(ItemCallEndMediaBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val model = getItem(position)) {
            is MediaUiModel.DayHeader -> (holder as? DayHolder)?.let { it.title.text = MediaFormat.dayTitle(it.title.context, model.dayKey) }
            is MediaUiModel.Item -> (holder as? TileHolder)?.bind(model, thumbPx, onOpen)
            null -> Unit
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TileHolder) clearImage(holder.binding.ivThumb)
        super.onViewRecycled(holder)
    }

    private class DayHolder(val title: TextView) : RecyclerView.ViewHolder(title)

    private class TileHolder(val binding: ItemCallEndMediaBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            val radius = 16 * binding.root.resources.displayMetrics.density
            binding.ivThumb.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            binding.ivThumb.clipToOutline = true
        }

        fun bind(model: MediaUiModel.Item, sizePx: Int, onOpen: (Long) -> Unit) {
            val media = model.media
            val isVideo = media.mediaType == MediaTypes.VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Glide.with(binding.ivThumb)
                .load(ContentUris.withAppendedId(base, media.mediaId))
                .override(sizePx, sizePx)
                .centerCrop()
                .dontAnimate()
                .placeholder(R.color.language_check_off)
                .into(binding.ivThumb)
            binding.tvDuration.visibility = if (isVideo) View.VISIBLE else View.GONE
            if (isVideo) binding.tvDuration.text = MediaFormat.duration(media.durationMs)
            binding.root.setOnClickListener { onOpen(media.uid) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaUiModel>() {
        override fun areItemsTheSame(oldItem: MediaUiModel, newItem: MediaUiModel): Boolean = when {
            oldItem is MediaUiModel.DayHeader && newItem is MediaUiModel.DayHeader -> oldItem.dayKey == newItem.dayKey
            oldItem is MediaUiModel.Item && newItem is MediaUiModel.Item -> oldItem.media.uid == newItem.media.uid
            else -> false
        }

        override fun areContentsTheSame(oldItem: MediaUiModel, newItem: MediaUiModel): Boolean = oldItem == newItem
    }

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_ITEM = 1
    }
}
