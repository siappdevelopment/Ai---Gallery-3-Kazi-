package com.example.thirdgallery.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.ItemPersonBinding
import com.example.thirdgallery.helper.FaceCropTransformation
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.PersonUi

/** Loads the face picture of a person: the photo cropped to the stored face box (a square around the face). */
fun loadFace(view: ImageView, person: PersonUi) {
    Glide.with(view)
        .load(MediaUris.of(person.mediaId, person.mediaType))
        .override(FACE_SOURCE_PX, FACE_SOURCE_PX)
        .transform(FaceCropTransformation(person.boxLeft, person.boxTop, person.boxRight, person.boxBottom))
        .dontAnimate()
        .placeholder(R.color.language_check_off)
        .into(view)
}

/** Stops loading a face picture; Glide.with() throws once the screen is destroyed, so that is checked first. */
fun clearFace(view: ImageView) {
    var context: Context? = view.context
    while (context is ContextWrapper) {
        if (context is Activity) {
            if (context.isDestroyed) return
            break
        }
        context = context.baseContext
    }
    Glide.with(view).clear(view)
}

private const val FACE_SOURCE_PX = 1024

/** The People row of For you: round face pictures with the name (or "Add name") and the photo count. */
class PeopleAdapter(
    private val onOpen: (PersonUi) -> Unit,
    private val onRename: (PersonUi) -> Unit,
) : ListAdapter<PersonUi, PeopleAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).clusterId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), onOpen, onRename)

    override fun onViewRecycled(holder: ViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class ViewHolder(private val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(person: PersonUi, onOpen: (PersonUi) -> Unit, onRename: (PersonUi) -> Unit) {
            val context = binding.root.context
            loadFace(binding.ivAvatar, person)
            val named = person.name != null
            binding.tvName.text = person.name ?: context.getString(R.string.people_add_name)
            binding.tvName.setTextColor(
                androidx.core.content.ContextCompat.getColor(context, if (named) R.color.splash_text_primary else R.color.perm_icon_tint),
            )
            binding.tvCount.text = person.photoCount.toString()
            binding.root.setOnClickListener { onOpen(person) }
            // An unnamed person: the pink "Add name" opens the name sheet; a long press renames a named one.
            binding.tvName.setOnClickListener { if (named) onOpen(person) else onRename(person) }
            binding.root.setOnLongClickListener {
                onRename(person)
                true
            }
        }

        fun clear() = clearFace(binding.ivAvatar)
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PersonUi>() {
            override fun areItemsTheSame(old: PersonUi, new: PersonUi) = old.clusterId == new.clusterId
            override fun areContentsTheSame(old: PersonUi, new: PersonUi) = old == new
        }
    }
}
