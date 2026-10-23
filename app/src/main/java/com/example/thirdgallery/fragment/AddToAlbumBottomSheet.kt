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
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.thirdgallery.adapter.PickAlbumAdapter
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.BottomSheetAddAlbumBinding
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.repo.AlbumRepository
import com.example.thirdgallery.viewmodel.AddToAlbumViewModel
import com.example.thirdgallery.viewmodel.PickAlbum
import com.example.thirdgallery.viewmodel.SelectionViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** "Add to album" sheet (design 46): pick an album or create a new one for the items of a day. */
class AddToAlbumBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddAlbumBinding? = null
    private val binding get() = _binding

    private val viewModel: AddToAlbumViewModel by viewModels()
    private val selectionViewModel: SelectionViewModel by activityViewModels()

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

        // The list scrolls on its own: it hands nothing to the sheet (which would drag it down) and the
        // sheet must not steal its touches. Dragging the handle or title still collapses the sheet.
        val albumAdapter = PickAlbumAdapter { viewModel.addTo(it) }
        b.rvAlbums.layoutManager = LinearLayoutManager(requireContext())
        b.rvAlbums.adapter = albumAdapter
        b.rvAlbums.itemAnimator = null
        b.rvAlbums.isNestedScrollingEnabled = false
        b.rvAlbums.setOnTouchListener { v, event ->
            // Keep the gesture only while the list can actually scroll in that direction.
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(v.canScrollVertically(-1) || v.canScrollVertically(1))
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        b.rowNewAlbum.setOnClickListener { showForm(true) }
        b.btnCancel.setOnClickListener { if (viewModel.isCreateOnly) dismissAllowingStateLoss() else showForm(false) }
        b.btnCreate.setOnClickListener { create() }
        b.etAlbumName.doAfterTextChanged { b.tvAlbumError.visibility = View.GONE }
        b.etAlbumName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) create()
            actionId == EditorInfo.IME_ACTION_DONE
        }

        val owner = viewLifecycleOwner
        if (viewModel.isCreateOnly) {
            // Opened from the Albums tab: straight to the name form.
            b.tvSheetTitle.setText(R.string.photo_new_album)
            if (savedInstanceState == null) showForm(true)
        } else {
            viewModel.itemCount.observe(owner) { count ->
                b.tvSheetTitle.text = if (viewModel.isMove) {
                    getString(R.string.selection_move_title, count)
                } else {
                    resources.getQuantityString(R.plurals.photo_add_to_album_title, count, count)
                }
            }
        }
        viewModel.albums.observe(owner) { renderAlbums(albumAdapter, it.orEmpty()) }
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
        // Open fully expanded: in the default half-height peek state the rows below the fold were cut off
        // and the sheet had to be dragged up before the rest of the list could be seen.
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
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

    private fun renderAlbums(adapter: PickAlbumAdapter, albums: List<PickAlbum>) {
        val b = binding ?: return
        adapter.submitList(albums.toList()) { capListHeight(b, albums.size) }
    }

    /** Shows about six rows at first; longer lists scroll inside the sheet instead of growing it. */
    private fun capListHeight(b: BottomSheetAddAlbumBinding, count: Int) {
        val density = resources.displayMetrics.density
        val maxPx = minOf(
            (VISIBLE_ROWS * ROW_HEIGHT_DP * density).toInt(),
            (resources.displayMetrics.heightPixels * MAX_LIST_SCREEN_FRACTION).toInt(),
        )
        val lp = b.rvAlbums.layoutParams
        val target = if (count > VISIBLE_ROWS) maxPx else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height != target) {
            lp.height = target
            b.rvAlbums.layoutParams = lp
        }
    }

    private fun handleResult(result: AlbumRepository.Result) {
        val b = binding
        when (result.failure) {
            null -> {
                Toast.makeText(
                    requireContext().applicationContext,
                    getString(
                        when {
                            viewModel.isCreateOnly -> R.string.albums_created
                            viewModel.isMove -> R.string.selection_moved_to
                            else -> R.string.photo_added_to_album
                        },
                        result.albumName,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                if (viewModel.isSelection) selectionViewModel.clear()
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
        private const val VISIBLE_ROWS = 6
        private const val ROW_HEIGHT_DP = 64f
        private const val MAX_LIST_SCREEN_FRACTION = 0.5f

        /** "New album" from the Albums tab: creates an empty album. */
        fun newCreateOnly() = AddToAlbumBottomSheet().apply {
            arguments = Bundle().apply {
                putLong(AddToAlbumViewModel.ARG_DAY_KEY, AddToAlbumViewModel.CREATE_ONLY)
                putInt(AddToAlbumViewModel.ARG_FILTER, 0)
                putInt(AddToAlbumViewModel.ARG_COUNT, 0)
            }
        }

        /** "Add to album" / "Move" for the multi-selection; [sourceAlbumId] is the app album Move takes the items out of. */
        fun newForSelection(uids: List<Long>, move: Boolean, sourceAlbumId: Long) = AddToAlbumBottomSheet().apply {
            arguments = Bundle().apply {
                putLong(AddToAlbumViewModel.ARG_DAY_KEY, AddToAlbumViewModel.SELECTION)
                putInt(AddToAlbumViewModel.ARG_FILTER, 0)
                putInt(AddToAlbumViewModel.ARG_COUNT, uids.size)
                putLongArray(AddToAlbumViewModel.ARG_UIDS, uids.toLongArray())
                putBoolean(AddToAlbumViewModel.ARG_MOVE, move)
                putLong(AddToAlbumViewModel.ARG_SOURCE, sourceAlbumId)
            }
        }

        fun newInstance(dayKey: Long, filter: MediaFilter, count: Int) = AddToAlbumBottomSheet().apply {
            arguments = Bundle().apply {
                putLong(AddToAlbumViewModel.ARG_DAY_KEY, dayKey)
                putInt(AddToAlbumViewModel.ARG_FILTER, filter.ordinal)
                putInt(AddToAlbumViewModel.ARG_COUNT, count)
            }
        }
    }
}
