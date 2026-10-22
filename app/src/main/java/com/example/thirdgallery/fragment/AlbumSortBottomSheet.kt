package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetLayoutPickerBinding
import com.example.thirdgallery.databinding.ItemSortOptionBinding
import com.example.thirdgallery.model.AlbumSort
import com.example.thirdgallery.viewmodel.AlbumsViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Sort order of the albums (Newest first / Oldest first / Name). */
class AlbumSortBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayoutPickerBinding? = null
    private val binding get() = _binding

    private val viewModel: AlbumsViewModel by activityViewModels()
    private val rows = mutableMapOf<AlbumSort, ItemSortOptionBinding>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetLayoutPickerBinding.inflate(inflater, container, false)
        _binding = b
        b.tvSheetTitle.setText(R.string.albums_sort_title)

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        rows.clear()
        OPTIONS.forEach { (sort, label) ->
            val row = ItemSortOptionBinding.inflate(inflater, b.optionsContainer, false)
            row.tvSortLabel.setText(label)
            row.root.setOnClickListener {
                viewModel.setSort(sort)
                dismissAllowingStateLoss()
            }
            b.optionsContainer.addView(row.root)
            rows[sort] = row
        }

        viewModel.sort.observe(viewLifecycleOwner) { selected ->
            rows.forEach { (sort, row) ->
                val on = sort == selected
                row.checkDot.isSelected = on
                row.ivCheck.visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
        }
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
    }

    override fun onDestroyView() {
        rows.clear()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "album_sort"

        private val OPTIONS: List<Pair<AlbumSort, Int>> = listOf(
            AlbumSort.NEWEST to R.string.albums_sort_newest,
            AlbumSort.OLDEST to R.string.albums_sort_oldest,
            AlbumSort.NAME to R.string.albums_sort_name,
        )
    }
}
