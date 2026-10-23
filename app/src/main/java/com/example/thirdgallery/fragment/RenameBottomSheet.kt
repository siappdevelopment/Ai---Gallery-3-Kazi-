package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetRenameBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Rename sheet, styled like the New album sheet. The file name is edited without its extension; the
 * new full name comes back as a fragment result and the activity does the system-authorised rename.
 */
class RenameBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRenameBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetRenameBinding.inflate(inflater, container, false)
        _binding = b
        val args = requireArguments()
        val extension = args.getString(ARG_EXTENSION).orEmpty()

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        if (savedInstanceState == null) {
            val name = args.getString(ARG_NAME).orEmpty()
            b.etName.setText(name)
            b.etName.setSelection(name.length)
        }
        b.etName.doAfterTextChanged { b.tvError.visibility = View.GONE }
        b.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) save(extension)
            actionId == EditorInfo.IME_ACTION_DONE
        }
        b.btnCancel.setOnClickListener {
            hideKeyboard()
            dismissAllowingStateLoss()
        }
        b.btnSave.setOnClickListener { save(extension) }
        b.etName.requestFocus()
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun save(extension: String) {
        val b = _binding ?: return
        val name = b.etName.text?.toString().orEmpty().trim()
        when {
            name.isEmpty() -> showError(R.string.viewer_rename_empty)
            name.any { it in FORBIDDEN } || name.startsWith(".") -> showError(R.string.viewer_rename_invalid)
            else -> {
                hideKeyboard()
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_NAME to name + extension))
                dismissAllowingStateLoss()
            }
        }
    }

    private fun showError(message: Int) {
        val b = _binding ?: return
        b.tvError.setText(message)
        b.tvError.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val b = _binding ?: return
        requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.etName.windowToken, 0)
    }

    companion object {
        const val RESULT_KEY = "viewer_rename"
        const val RESULT_NAME = "name"
        private const val ARG_NAME = "name"
        private const val ARG_EXTENSION = "extension"
        private const val TAG = "rename_sheet"
        private const val FORBIDDEN = "/\\:*?\"<>|"

        /** [fileName] is the full current name, e.g. IMG_0001.jpg; only the part before the extension is edited. */
        fun show(fm: FragmentManager, fileName: String) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            val dot = fileName.lastIndexOf('.')
            val hasExtension = dot > 0
            RenameBottomSheet().apply {
                arguments = bundleOf(
                    ARG_NAME to if (hasExtension) fileName.substring(0, dot) else fileName,
                    ARG_EXTENSION to if (hasExtension) fileName.substring(dot) else "",
                )
            }.show(fm, TAG)
        }
    }
}
