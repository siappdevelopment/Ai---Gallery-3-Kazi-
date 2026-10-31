package com.example.thirdgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.databinding.ItemPeopleMessageBinding
import com.example.thirdgallery.databinding.ItemPeoplePersonBinding
import com.example.thirdgallery.databinding.ItemPeopleSectionBinding
import com.example.thirdgallery.databinding.ItemPeopleUnnamedBinding
import com.example.thirdgallery.R
import com.example.thirdgallery.model.PersonUi

/** One row of the People & faces screen. */
sealed class PeopleRow {
    data class Person(val person: PersonUi) : PeopleRow()
    data class Unnamed(val person: PersonUi) : PeopleRow()
    data class Section(@StringRes val title: Int) : PeopleRow()
    data class Message(val text: String) : PeopleRow()
}

/** The People & faces grid: people (3 per row), the "Unnamed groups" heading, unnamed groups (3 per row) and messages. */
class PeopleGridAdapter(
    private val onOpen: (PersonUi) -> Unit,
    private val onRename: (PersonUi) -> Unit,
) : ListAdapter<PeopleRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is PeopleRow.Person -> TYPE_PERSON
        is PeopleRow.Unnamed -> TYPE_UNNAMED
        is PeopleRow.Section -> TYPE_SECTION
        is PeopleRow.Message -> TYPE_MESSAGE
    }

    /** Headings and messages take the whole row. */
    fun isFullWidth(position: Int): Boolean = getItemViewType(position).let { it == TYPE_SECTION || it == TYPE_MESSAGE }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PERSON -> PersonHolder(ItemPeoplePersonBinding.inflate(inflater, parent, false))
            TYPE_UNNAMED -> UnnamedHolder(ItemPeopleUnnamedBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemPeopleSectionBinding.inflate(inflater, parent, false))
            else -> MessageHolder(ItemPeopleMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is PeopleRow.Person -> (holder as PersonHolder).bind(row.person, onOpen, onRename)
            is PeopleRow.Unnamed -> (holder as UnnamedHolder).bind(row.person, onOpen, onRename)
            is PeopleRow.Section -> (holder as SectionHolder).binding.tvSection.setText(row.title)
            is PeopleRow.Message -> (holder as MessageHolder).binding.tvMessage.text = row.text
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is PersonHolder -> clearFace(holder.binding.ivAvatar)
            is UnnamedHolder -> clearFace(holder.binding.ivAvatar)
        }
        super.onViewRecycled(holder)
    }

    private class PersonHolder(val binding: ItemPeoplePersonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(person: PersonUi, onOpen: (PersonUi) -> Unit, onRename: (PersonUi) -> Unit) {
            val context = binding.root.context
            loadFace(binding.ivAvatar, person)
            val named = person.name != null
            binding.tvName.text = person.name ?: context.getString(R.string.people_add_name)
            binding.tvName.setTextColor(
                androidx.core.content.ContextCompat.getColor(context, if (named) R.color.splash_text_primary else R.color.perm_icon_tint),
            )
            binding.tvCount.text = context.resources.getQuantityString(R.plurals.intro2_photos, person.photoCount, person.photoCount)
            binding.root.setOnClickListener { onOpen(person) }
            binding.tvName.setOnClickListener { if (named) onOpen(person) else onRename(person) }
            binding.root.setOnLongClickListener {
                onRename(person)
                true
            }
        }
    }

    private class UnnamedHolder(val binding: ItemPeopleUnnamedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(person: PersonUi, onOpen: (PersonUi) -> Unit, onRename: (PersonUi) -> Unit) {
            val context = binding.root.context
            loadFace(binding.ivAvatar, person)
            binding.tvCount.text = context.resources.getQuantityString(R.plurals.intro2_photos, person.photoCount, person.photoCount)
            binding.root.setOnClickListener { onOpen(person) }
            binding.btnAddName.setOnClickListener { onRename(person) }
        }
    }

    private class SectionHolder(val binding: ItemPeopleSectionBinding) : RecyclerView.ViewHolder(binding.root)

    private class MessageHolder(val binding: ItemPeopleMessageBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_PERSON = 0
        const val TYPE_UNNAMED = 1
        const val TYPE_SECTION = 2
        const val TYPE_MESSAGE = 3

        val DIFF = object : DiffUtil.ItemCallback<PeopleRow>() {
            override fun areItemsTheSame(old: PeopleRow, new: PeopleRow): Boolean = when {
                old is PeopleRow.Person && new is PeopleRow.Person -> old.person.clusterId == new.person.clusterId
                old is PeopleRow.Unnamed && new is PeopleRow.Unnamed -> old.person.clusterId == new.person.clusterId
                old is PeopleRow.Section && new is PeopleRow.Section -> old.title == new.title
                old is PeopleRow.Message && new is PeopleRow.Message -> true
                else -> false
            }

            override fun areContentsTheSame(old: PeopleRow, new: PeopleRow) = old == new
        }
    }
}
