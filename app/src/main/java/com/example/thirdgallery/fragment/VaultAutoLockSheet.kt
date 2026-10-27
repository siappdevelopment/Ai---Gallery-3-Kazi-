package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetLayoutPickerBinding
import com.example.thirdgallery.databinding.ItemSortOptionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Auto-lock options (design 79): a radio list; the chosen position comes back as a fragment result. */
class VaultAutoLockSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLayoutPickerBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetLayoutPickerBinding.inflate(inflater, container, false)
        _binding = b
        b.tvSheetTitle.setText(R.string.vault_auto_lock)

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        val selected = requireArguments().getInt(ARG_SELECTED)
        OPTIONS.forEachIndexed { index, label ->
            val row = ItemSortOptionBinding.inflate(inflater, b.optionsContainer, false)
            row.tvSortLabel.setText(label)
            row.checkDot.isSelected = index == selected
            row.ivCheck.visibility = if (index == selected) View.VISIBLE else View.INVISIBLE
            row.root.setOnClickListener {
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_OPTION to index))
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
        const val RESULT_KEY = "vault_auto_lock"
        const val RESULT_OPTION = "option"
        private const val ARG_SELECTED = "selected"
        private const val TAG = "vault_auto_lock_sheet"

        /** Labels in the order of VaultPrefs.AUTO_LOCK_*. */
        val OPTIONS = listOf(
            R.string.vault_auto_lock_immediately,
            R.string.vault_auto_lock_1_minute,
            R.string.vault_auto_lock_5_minutes,
            R.string.vault_auto_lock_screen_off,
        )

        fun show(fm: FragmentManager, selected: Int) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            VaultAutoLockSheet().apply { arguments = bundleOf(ARG_SELECTED to selected) }.show(fm, TAG)
        }
    }
}
