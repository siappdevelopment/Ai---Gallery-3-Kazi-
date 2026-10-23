package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import com.example.thirdgallery.databinding.BottomSheetMoreBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** "More options" sheet of the photo viewer (design 34); the chosen row comes back as a fragment result. */
class MoreOptionsBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetMoreBinding.inflate(inflater, container, false)

        // Keep the last row clear of the gesture / navigation bar.
        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        b.rowAdd.setOnClickListener { choose(ACTION_ADD) }
        b.rowMove.setOnClickListener { choose(ACTION_MOVE) }
        b.rowCopy.setOnClickListener { choose(ACTION_COPY) }
        b.rowRename.setOnClickListener { choose(ACTION_RENAME) }
        b.rowWallpaper.setOnClickListener { choose(ACTION_WALLPAPER) }
        b.rowVault.setOnClickListener { choose(ACTION_VAULT) }
        return b.root
    }

    override fun onStart() {
        super.onStart()
        // The sheet draws its own rounded background, so the container must not add another.
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun choose(action: String) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_ACTION to action))
        dismissAllowingStateLoss()
    }

    companion object {
        const val RESULT_KEY = "viewer_more_options"
        const val RESULT_ACTION = "action"
        const val ACTION_ADD = "add"
        const val ACTION_MOVE = "move"
        const val ACTION_COPY = "copy"
        const val ACTION_RENAME = "rename"
        const val ACTION_WALLPAPER = "wallpaper"
        const val ACTION_VAULT = "vault"
        private const val TAG = "more_options"

        fun show(fm: FragmentManager) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            MoreOptionsBottomSheet().show(fm, TAG)
        }
    }
}
