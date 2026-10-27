package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetVaultFolderBinding
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.viewmodel.VaultMediaViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** "New vault folder" (design 71): a name field with Cancel and Create; an empty or already used name shows an error. */
class VaultFolderSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVaultFolderBinding? = null
    private val viewModel: VaultMediaViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetVaultFolderBinding.inflate(inflater, container, false)
        _binding = b

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        b.etName.doAfterTextChanged { b.tvError.visibility = View.GONE }
        b.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) create()
            actionId == EditorInfo.IME_ACTION_DONE
        }
        b.btnCancel.setOnClickListener {
            hideKeyboard()
            dismissAllowingStateLoss()
        }
        b.btnCreate.setOnClickListener { create() }
        b.etName.requestFocus()

        viewModel.folderResult.observe(viewLifecycleOwner) { event ->
            when (event.getContentIfNotHandled()) {
                VaultRepository.FolderResult.CREATED -> {
                    hideKeyboard()
                    Toast.makeText(requireContext().applicationContext, R.string.vault_folder_created, Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                }
                VaultRepository.FolderResult.EMPTY -> showError(R.string.vault_folder_name_empty)
                VaultRepository.FolderResult.EXISTS -> showError(R.string.vault_folder_name_exists)
                null -> Unit
            }
        }
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

    private fun create() {
        val b = _binding ?: return
        viewModel.createFolder(b.etName.text?.toString().orEmpty())
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
        private const val TAG = "vault_folder_sheet"

        fun show(fm: FragmentManager) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            VaultFolderSheet().show(fm, TAG)
        }
    }
}
