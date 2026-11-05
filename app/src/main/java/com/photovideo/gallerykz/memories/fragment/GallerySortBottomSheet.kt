package com.photovideo.gallerykz.memories.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.BottomSheetLayoutPickerBinding
import com.photovideo.gallerykz.memories.databinding.ItemSortOptionBinding
import com.photovideo.gallerykz.memories.model.GallerySort

/** Sort order of the Gallery grid: date, name or size. The choice goes back as a fragment result. */
class GallerySortBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayoutPickerBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetLayoutPickerBinding.inflate(inflater, container, false)
        _binding = b
        b.tvSheetTitle.setText(R.string.gallery_sort_title)

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        val selected = GallerySort.entries.getOrNull(requireArguments().getInt(ARG_SELECTED)) ?: GallerySort.DATE_NEWEST
        GallerySort.entries.forEach { sort ->
            val row = ItemSortOptionBinding.inflate(inflater, b.optionsContainer, false)
            row.tvSortLabel.setText(labelOf(sort))
            val on = sort == selected
            row.checkDot.isSelected = on
            row.ivCheck.visibility = if (on) View.VISIBLE else View.INVISIBLE
            row.root.setOnClickListener {
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_SORT to sort.ordinal))
                dismissAllowingStateLoss()
            }
            b.optionsContainer.addView(row.root)
        }
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "gallery_sort"
        const val RESULT_SORT = "sort"
        private const val ARG_SELECTED = "selected"
        private const val TAG = "gallery_sort_sheet"

        @StringRes
        fun labelOf(sort: GallerySort): Int = when (sort) {
            GallerySort.DATE_NEWEST -> R.string.gallery_sort_date_newest
            GallerySort.DATE_OLDEST -> R.string.gallery_sort_date_oldest
            GallerySort.NAME_AZ -> R.string.gallery_sort_name_az
            GallerySort.NAME_ZA -> R.string.gallery_sort_name_za
            GallerySort.SIZE_LARGEST -> R.string.gallery_sort_size_largest
            GallerySort.SIZE_SMALLEST -> R.string.gallery_sort_size_smallest
        }

        fun show(fm: FragmentManager, current: GallerySort) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            GallerySortBottomSheet().apply { arguments = bundleOf(ARG_SELECTED to current.ordinal) }.show(fm, TAG)
        }
    }
}
