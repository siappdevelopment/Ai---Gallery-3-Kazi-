package com.example.thirdgallery.launcher

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.databinding.ItemLauncherAppBinding

/**
 * A grid of apps (the drawer and the folder pop-up). Icon size, name size and name visibility follow the launcher
 * settings; [onLongClick] is optional (folders have no menu).
 */
class LauncherAppsAdapter(
    private val onClick: (LauncherApp) -> Unit,
    private val onLongClick: ((LauncherApp, View) -> Unit)? = null,
) : ListAdapter<LauncherApp, LauncherAppsAdapter.Holder>(Diff) {

    private var iconDp = LauncherSettings.ICON_DEFAULT
    private var labelSp = LauncherSettings.LABEL_DEFAULT
    private var labelVisible = true

    /** Applies the display settings; the visible rows are re-bound. */
    fun applySettings(context: Context) {
        iconDp = LauncherSettings.getIconSize(context)
        labelSp = LauncherSettings.getLabelSize(context)
        labelVisible = LauncherSettings.isLabelVisible(context)
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLauncherAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        val b = holder.binding
        val side = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, iconDp.toFloat(), b.root.resources.displayMetrics).toInt()
        b.ivIcon.layoutParams = b.ivIcon.layoutParams.apply {
            width = side
            height = side
        }
        LauncherIcons.bind(b.ivIcon, app.icon, side)
        b.tvLabel.text = app.label
        b.tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp.toFloat())
        b.tvLabel.visibility = if (labelVisible) View.VISIBLE else View.GONE
        b.root.contentDescription = app.label
        b.root.setOnClickListener { onClick(app) }
        val longClick = onLongClick
        if (longClick != null) {
            b.root.setOnLongClickListener {
                longClick(app, b.root)
                true
            }
        } else {
            b.root.setOnLongClickListener(null)
            b.root.isLongClickable = false
        }
    }

    class Holder(val binding: ItemLauncherAppBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<LauncherApp>() {
        override fun areItemsTheSame(a: LauncherApp, b: LauncherApp) = a.key == b.key
        override fun areContentsTheSame(a: LauncherApp, b: LauncherApp) = a.label == b.label && a.icon === b.icon
    }
}
