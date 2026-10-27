package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetLayoutPickerBinding
import com.example.thirdgallery.databinding.ItemVaultFolderRowBinding
import com.example.thirdgallery.viewmodel.VaultFolderViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** "Move to folder" (design 74): the vault's folders with their item counts; the pick comes back as a fragment result. */
class VaultMoveSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayoutPickerBinding? = null
    private val viewModel: VaultFolderViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetLayoutPickerBinding.inflate(inflater, container, false)
        _binding = b
        b.tvSheetTitle.setText(R.string.vault_move_to_folder)

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        // Rebuilt whenever the folders or their items change, so the counts are always current.
        val render = {
            val folders = viewModel.folders.value.orEmpty()
            val items = viewModel.everything.value.orEmpty()
            b.optionsContainer.removeAllViews()
            folders.forEach { folder ->
                val row = ItemVaultFolderRowBinding.inflate(inflater, b.optionsContainer, false)
                val count = items.count { it.folderId == folder.id }
                row.tvName.text = folder.name
                row.tvCount.text = row.root.resources.getQuantityString(R.plurals.vault_folder_items, count, count)
                row.root.setOnClickListener {
                    parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_FOLDER to folder.id))
                    dismissAllowingStateLoss()
                }
                b.optionsContainer.addView(row.root)
            }
        }
        viewModel.folders.observe(viewLifecycleOwner) { render() }
        viewModel.everything.observe(viewLifecycleOwner) { render() }
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
        const val RESULT_KEY = "vault_move_folder"
        const val RESULT_FOLDER = "folder"
        private const val TAG = "vault_move_sheet"

        fun show(fm: FragmentManager) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            VaultMoveSheet().show(fm, TAG)
        }
    }
}
