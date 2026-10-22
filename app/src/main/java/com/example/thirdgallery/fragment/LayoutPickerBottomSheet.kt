package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetLayoutPickerBinding
import com.example.thirdgallery.databinding.ItemLayoutOptionBinding
import com.example.thirdgallery.model.GalleryLayout
import com.example.thirdgallery.viewmodel.GalleryLayoutViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** "Gallery layout" picker (design 17 Layout Picker). */
class LayoutPickerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayoutPickerBinding? = null
    private val binding get() = _binding

    private val layoutViewModel: GalleryLayoutViewModel by activityViewModels()
    private val rows = mutableMapOf<GalleryLayout, ItemLayoutOptionBinding>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetLayoutPickerBinding.inflate(inflater, container, false)
        _binding = b

        // Keep the last row clear of the gesture / navigation bar.
        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        rows.clear()
        OPTIONS.forEach { option ->
            val row = ItemLayoutOptionBinding.inflate(inflater, b.optionsContainer, false)
            row.ivOptionIcon.setImageResource(option.icon)
            row.tvOptionTitle.setText(option.title)
            row.tvOptionDesc.setText(option.description)
            if (option.layout.implemented) {
                row.optionRoot.setOnClickListener {
                    layoutViewModel.select(option.layout)
                    dismissAllowingStateLoss()
                }
            } else {
                // Not built yet: shown like the design but dimmed and not selectable.
                row.optionRoot.alpha = 0.45f
                row.optionRoot.isClickable = false
                row.optionRoot.isFocusable = false
            }
            b.optionsContainer.addView(row.root)
            rows[option.layout] = row
        }

        layoutViewModel.layout.observe(viewLifecycleOwner) { selected ->
            rows.forEach { (layout, row) ->
                val on = layout == selected
                row.optionRoot.isSelected = on
                row.iconTile.isSelected = on
                row.ivOptionIcon.isSelected = on
                row.checkDot.isSelected = on
                row.ivCheck.visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
        }
        return b.root
    }

    override fun onStart() {
        super.onStart()
        // The sheet draws its own rounded background, so the container must not add another.
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
    }

    override fun onDestroyView() {
        rows.clear()
        _binding = null
        super.onDestroyView()
    }

    private data class Option(
        val layout: GalleryLayout,
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val description: Int,
    )

    companion object {
        const val TAG = "layout_picker"

        private val OPTIONS = listOf(
            Option(GalleryLayout.CLASSIC, R.drawable.ic_grid_view, R.string.photo_layout_classic, R.string.photo_layout_classic_desc),
            Option(GalleryLayout.TIMELINE, R.drawable.ic_view_timeline, R.string.photo_layout_timeline, R.string.photo_layout_timeline_desc),
            Option(GalleryLayout.MOSAIC, R.drawable.ic_dashboard, R.string.photo_layout_mosaic, R.string.photo_layout_mosaic_desc),
            Option(GalleryLayout.JOURNAL, R.drawable.ic_auto_stories, R.string.photo_layout_journal, R.string.photo_layout_journal_desc),
        )
    }
}
