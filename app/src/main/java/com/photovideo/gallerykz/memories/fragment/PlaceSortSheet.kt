package com.photovideo.gallerykz.memories.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.BottomSheetLayoutPickerBinding
import com.photovideo.gallerykz.memories.databinding.ItemSortOptionBinding
import com.photovideo.gallerykz.memories.viewmodel.PlacesViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Sort order of the places (Most photos / Name / Newest first); the pick comes back as a fragment result. */
class PlaceSortSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayoutPickerBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetLayoutPickerBinding.inflate(inflater, container, false)
        _binding = b
        b.tvSheetTitle.setText(R.string.places_sort_title)

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        val selected = requireArguments().getInt(ARG_SELECTED)
        OPTIONS.forEachIndexed { index, (_, label) ->
            val row = ItemSortOptionBinding.inflate(inflater, b.optionsContainer, false)
            row.tvSortLabel.setText(label)
            row.checkDot.isSelected = index == selected
            row.ivCheck.visibility = if (index == selected) View.VISIBLE else View.INVISIBLE
            row.root.setOnClickListener {
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_SORT to index))
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
        const val RESULT_KEY = "place_sort"
        const val RESULT_SORT = "sort"
        private const val ARG_SELECTED = "selected"
        private const val TAG = "place_sort_sheet"

        private val OPTIONS = listOf(
            PlacesViewModel.Sort.MOST_PHOTOS to R.string.places_sort_most,
            PlacesViewModel.Sort.NAME to R.string.places_sort_name,
            PlacesViewModel.Sort.NEWEST to R.string.places_sort_newest,
        )

        fun sortOf(index: Int): PlacesViewModel.Sort = OPTIONS.getOrNull(index)?.first ?: PlacesViewModel.Sort.MOST_PHOTOS

        fun show(fm: FragmentManager, selected: PlacesViewModel.Sort) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            PlaceSortSheet().apply {
                arguments = bundleOf(ARG_SELECTED to OPTIONS.indexOfFirst { it.first == selected }.coerceAtLeast(0))
            }.show(fm, TAG)
        }
    }
}
