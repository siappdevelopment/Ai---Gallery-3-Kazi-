package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.databinding.ItemLanguageBinding
import com.example.thirdgallery.model.LanguageItem
import com.example.thirdgallery.model.LanguageUiItem
import java.util.concurrent.Executor

/** Only renders rows and reports taps; selection state comes from the ViewModel. */
class LanguageAdapter(
    private val onLanguageClick: (LanguageItem) -> Unit,
) : ListAdapter<LanguageUiItem, LanguageAdapter.ViewHolder>(
    // The list is tiny, so diff on the calling thread for immediate visual feedback.
    AsyncDifferConfig.Builder(DIFF).setBackgroundThreadExecutor(Executor { it.run() }).build()
) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).language.tag.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position).isSelected)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(private val binding: ItemLanguageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                onLanguageClick(getItem(position).language)
            }
        }

        fun bind(item: LanguageUiItem) {
            binding.tvFlag.text = item.language.flag
            binding.tvName.text = item.language.nativeName
            binding.tvNative.text = item.language.englishName
            bindSelection(item.isSelected)
        }

        fun bindSelection(selected: Boolean) {
            binding.root.isSelected = selected
            binding.flCheck.isSelected = selected
            binding.ivCheck.alpha = if (selected) 1f else 0f
        }
    }

    private companion object {
        const val PAYLOAD_SELECTION = "selection"

        val DIFF = object : DiffUtil.ItemCallback<LanguageUiItem>() {
            override fun areItemsTheSame(old: LanguageUiItem, new: LanguageUiItem) =
                old.language.tag == new.language.tag

            override fun areContentsTheSame(old: LanguageUiItem, new: LanguageUiItem) = old == new

            override fun getChangePayload(old: LanguageUiItem, new: LanguageUiItem): Any? =
                if (old.language == new.language && old.isSelected != new.isSelected) PAYLOAD_SELECTION else null
        }
    }
}
