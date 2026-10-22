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
import androidx.fragment.app.viewModels
import com.bumptech.glide.Glide
import com.example.thirdgallery.R
import com.example.thirdgallery.database.UserAlbumRow
import com.example.thirdgallery.databinding.BottomSheetAddAlbumBinding
import com.example.thirdgallery.databinding.ItemAlbumPickBinding
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.AlbumRepository
import com.example.thirdgallery.viewmodel.AddToAlbumViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** "Add to album" sheet (design 46): pick an album or create a new one for the items of a day. */
class AddToAlbumBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddAlbumBinding? = null
    private val binding get() = _binding

    private val viewModel: AddToAlbumViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetAddAlbumBinding.inflate(inflater, container, false)
        _binding = b

        // Keep the last row clear of the gesture / navigation bar.
        val basePadding = b.sheetRoot.getChildAt(0).paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val content = b.sheetRoot.getChildAt(0)
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, basePadding + bottom)
            insets
        }

        b.rowNewAlbum.setOnClickListener { showForm(true) }
        b.btnCancel.setOnClickListener { showForm(false) }
        b.btnCreate.setOnClickListener { create() }
        b.etAlbumName.doAfterTextChanged { b.tvAlbumError.visibility = View.GONE }
        b.etAlbumName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) create()
            actionId == EditorInfo.IME_ACTION_DONE
        }

        val owner = viewLifecycleOwner
        viewModel.itemCount.observe(owner) { count ->
            b.tvSheetTitle.text = resources.getQuantityString(R.plurals.photo_add_to_album_title, count, count)
        }
        viewModel.albums.observe(owner) { renderAlbums(it.orEmpty()) }
        viewModel.result.observe(owner) { event ->
            event.getContentIfNotHandled()?.let { handleResult(it) }
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
        binding?.let { b ->
            b.albumsContainer.removeAllViews()
        }
        _binding = null
        super.onDestroyView()
    }

    private fun showForm(show: Boolean) {
        val b = binding ?: return
        b.listGroup.visibility = if (show) View.GONE else View.VISIBLE
        b.formGroup.visibility = if (show) View.VISIBLE else View.GONE
        b.tvAlbumError.visibility = View.GONE
        if (show) {
            b.etAlbumName.requestFocus()
        } else {
            hideKeyboard()
        }
    }

    private fun create() {
        val b = binding ?: return
        hideKeyboard()
        viewModel.createAndAdd(b.etAlbumName.text?.toString().orEmpty())
    }

    private fun renderAlbums(albums: List<UserAlbumRow>) {
        val b = binding ?: return
        b.albumsContainer.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        albums.forEach { album ->
            val row = ItemAlbumPickBinding.inflate(inflater, b.albumsContainer, false)
            row.tvAlbumName.text = album.name
            row.tvAlbumCount.text = getString(R.string.photo_items, album.total)
            val cover = album.cover
            if (cover != null) {
                val px = (48 * resources.displayMetrics.density).toInt()
                Glide.with(row.ivAlbumCover).load(MediaUris.ofUid(cover)).override(px, px).centerCrop().dontAnimate()
                    .into(row.ivAlbumCover)
            }
            row.root.setOnClickListener { viewModel.addTo(album) }
            b.albumsContainer.addView(row.root)
        }
    }

    private fun handleResult(result: AlbumRepository.Result) {
        val b = binding
        when (result.failure) {
            null -> {
                Toast.makeText(
                    requireContext().applicationContext,
                    getString(R.string.photo_added_to_album, result.albumName),
                    Toast.LENGTH_SHORT,
                ).show()
                dismissAllowingStateLoss()
            }
            AlbumRepository.Failure.EMPTY_NAME -> showError(b, R.string.photo_album_name_empty)
            AlbumRepository.Failure.NAME_EXISTS -> showError(b, R.string.photo_album_name_exists)
            AlbumRepository.Failure.FAILED -> showError(b, R.string.photo_add_failed)
        }
    }

    private fun showError(b: BottomSheetAddAlbumBinding?, message: Int) {
        if (b == null) return
        if (b.formGroup.visibility == View.VISIBLE) {
            b.tvAlbumError.setText(message)
            b.tvAlbumError.visibility = View.VISIBLE
        } else {
            Toast.makeText(requireContext().applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val b = binding ?: return
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.etAlbumName.windowToken, 0)
    }

    companion object {
        const val TAG = "add_to_album"

        fun newInstance(dayKey: Long, filter: MediaFilter, count: Int) = AddToAlbumBottomSheet().apply {
            arguments = Bundle().apply {
                putLong(AddToAlbumViewModel.ARG_DAY_KEY, dayKey)
                putInt(AddToAlbumViewModel.ARG_FILTER, filter.ordinal)
                putInt(AddToAlbumViewModel.ARG_COUNT, count)
            }
        }
    }
}
