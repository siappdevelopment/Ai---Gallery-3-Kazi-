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
import androidx.fragment.app.FragmentManager
import com.example.thirdgallery.databinding.BottomSheetPersonNameBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Name a person (a face group): a name field with Cancel and Save. The answer comes back as a fragment result with
 * the group's id and the typed name; an empty name takes the name away again.
 */
class PersonNameSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPersonNameBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetPersonNameBinding.inflate(inflater, container, false)
        _binding = b
        val args = requireArguments()

        // Only the system bars: the dialog window already resizes above the keyboard (like the Rename sheet).
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
        b.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) save()
            actionId == EditorInfo.IME_ACTION_DONE
        }
        b.btnCancel.setOnClickListener {
            hideKeyboard()
            dismissAllowingStateLoss()
        }
        b.btnSave.setOnClickListener { save() }
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

    private fun save() {
        val b = _binding ?: return
        hideKeyboard()
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(RESULT_ID to requireArguments().getLong(ARG_ID), RESULT_NAME to b.etName.text?.toString().orEmpty().trim()),
        )
        dismissAllowingStateLoss()
    }

    private fun hideKeyboard() {
        val b = _binding ?: return
        requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.etName.windowToken, 0)
    }

    companion object {
        const val RESULT_KEY = "person_name"
        const val RESULT_ID = "id"
        const val RESULT_NAME = "name"
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val TAG = "person_name_sheet"

        fun show(fm: FragmentManager, clusterId: Long, currentName: String?) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            PersonNameSheet().apply { arguments = bundleOf(ARG_ID to clusterId, ARG_NAME to currentName.orEmpty()) }.show(fm, TAG)
        }
    }
}
