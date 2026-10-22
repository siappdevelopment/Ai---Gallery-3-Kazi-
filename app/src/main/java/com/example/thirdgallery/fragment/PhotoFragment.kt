package com.example.thirdgallery.fragment

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.activity.HomeActivity
import com.example.thirdgallery.adapter.FilterEmptyAdapter
import com.example.thirdgallery.adapter.GalleryHeaderAdapter
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.databinding.FragmentPhotoBinding
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.GalleryLayout
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.viewmodel.GalleryLayoutViewModel
import com.example.thirdgallery.viewmodel.PhotoViewModel

/**
 * Gallery tab: every photo and video on the device, grouped by day, paged from Room.
 * In the Classic layout a header row ("Your library" albums + filter) scrolls above the grid.
 */
class PhotoFragment : Fragment() {

    private enum class UiState { CONTENT, LOADING, EMPTY, FILTER_EMPTY, ERROR, PERMISSION }

    private var _binding: FragmentPhotoBinding? = null
    private val binding get() = _binding

    private val viewModel: PhotoViewModel by viewModels()
    private val layoutViewModel: GalleryLayoutViewModel by activityViewModels()

    private var mediaAdapter: MediaPagingAdapter? = null
    private var headerAdapter: GalleryHeaderAdapter? = null
    private var filterEmptyAdapter: FilterEmptyAdapter? = null
    private var refreshState: LoadState? = null
    private var hasAccess = false
    private var requesting = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requesting = false
        val granted = MediaPermissionHelper.hasAccess(requireContext())
        if (!granted && !MediaPermissionHelper.requiredPermissions().any { shouldShowRequestPermissionRationale(it) }) {
            // "Don't ask again": the only way back is the app's Settings page.
            activity?.let { MediaPermissionHelper.openAppSettings(it) }
        }
        syncAccess()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentPhotoBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        // About 90dp per column (4 on a normal phone); thumbnails are decoded at tile size only.
        val spanCount = (resources.configuration.screenWidthDp / 90).coerceIn(3, 8)
        val thumbPx = (resources.displayMetrics.widthPixels / spanCount).coerceAtLeast(1)
        val media = MediaPagingAdapter(thumbPx)
        val header = GalleryHeaderAdapter(
            onFilterSelected = { viewModel.setFilter(it) },
            onAllAlbums = { (activity as? HomeActivity)?.openAlbumsTab() },
        )
        val filterEmpty = FilterEmptyAdapter()
        mediaAdapter = media
        headerAdapter = header
        filterEmptyAdapter = filterEmpty

        // header row, then the paged photos, then (only when a filter matches nothing) the empty row
        val concat = ConcatAdapter(header, media, filterEmpty)
        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val headerCount = header.itemCount
                if (position < headerCount) return spanCount
                val index = position - headerCount
                return if (index >= media.itemCount || media.isHeader(index)) spanCount else 1
            }
        }
        b.rvMedia.layoutManager = layoutManager
        b.rvMedia.adapter = concat
        b.rvMedia.itemAnimator = null
        b.rvMedia.setHasFixedSize(true)
        b.rvMedia.setItemViewCacheSize(spanCount * 3)

        media.addLoadStateListener { states ->
            refreshState = states.refresh
            render()
        }

        val owner = viewLifecycleOwner
        viewModel.media.observe(owner) { data -> media.submitData(owner.lifecycle, data) }
        viewModel.dayCounts.observe(owner) { media.updateCounts(it) }
        viewModel.filter.observe(owner) {
            header.setFilter(it)
            render()
        }
        viewModel.libraryAlbums.observe(owner) { header.setContent(viewModel.totalCount.value ?: 0, it.orEmpty()) }
        viewModel.totalCount.observe(owner) { header.setContent(it ?: 0, viewModel.libraryAlbums.value.orEmpty()) }
        viewModel.syncing.observe(owner) { render() }
        viewModel.synced.observe(owner) { render() }
        viewModel.syncError.observe(owner) { render() }
        // Switching layouts only swaps the header; the photo list is never reloaded.
        layoutViewModel.layout.observe(owner) { render() }

        hasAccess = MediaPermissionHelper.hasAccess(requireContext())
        render()
    }

    override fun onStart() {
        super.onStart()
        if (hasAccess) viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted or revoked in Settings while the app was in the background.
        syncAccess()
    }

    override fun onDestroyView() {
        binding?.rvMedia?.adapter = null
        mediaAdapter = null
        headerAdapter = null
        filterEmptyAdapter = null
        refreshState = null
        _binding = null
        super.onDestroyView()
    }

    private fun syncAccess() {
        if (_binding == null) return
        val granted = MediaPermissionHelper.hasAccess(requireContext())
        if (granted != hasAccess) {
            hasAccess = granted
            if (granted) viewModel.refresh(force = true) else viewModel.onAccessLost()
        }
        render()
    }

    private fun requestAccess() {
        if (requesting) return
        requesting = true
        try {
            permissionLauncher.launch(MediaPermissionHelper.requiredPermissions())
        } catch (e: Exception) {
            requesting = false
        }
    }

    private fun currentState(): UiState {
        val itemCount = mediaAdapter?.itemCount ?: 0
        val synced = viewModel.synced.value == true
        val filter = viewModel.filter.value ?: MediaFilter.ALL
        return when {
            !hasAccess -> UiState.PERMISSION
            itemCount > 0 -> UiState.CONTENT
            refreshState is LoadState.Error || viewModel.syncError.value == true -> UiState.ERROR
            viewModel.syncing.value == true || !synced || refreshState is LoadState.Loading -> UiState.LOADING
            filter == MediaFilter.ALL -> UiState.EMPTY
            else -> UiState.FILTER_EMPTY
        }
    }

    private fun render() {
        val b = binding ?: return
        val state = currentState()
        val classic = (layoutViewModel.layout.value ?: GalleryLayout.CLASSIC) == GalleryLayout.CLASSIC

        // The grid stays visible when a filter matches nothing, so the filter bar stays usable.
        val listVisible = state == UiState.CONTENT || state == UiState.FILTER_EMPTY
        b.rvMedia.visibility = if (listVisible) View.VISIBLE else View.INVISIBLE
        headerAdapter?.setVisible(classic && listVisible)
        filterEmptyAdapter?.setVisible(state == UiState.FILTER_EMPTY)

        b.progress.visibility = if (state == UiState.LOADING) View.VISIBLE else View.GONE
        val showState = state == UiState.EMPTY || state == UiState.ERROR || state == UiState.PERMISSION
        b.stateGroup.visibility = if (showState) View.VISIBLE else View.GONE
        if (!showState) return

        when (state) {
            UiState.EMPTY -> showState(
                R.drawable.ic_photo_library, R.string.photo_empty_title, R.string.photo_empty_desc,
                R.string.photo_open_camera,
            ) { openCamera() }
            UiState.ERROR -> showState(
                R.drawable.ic_error, R.string.photo_error_title, R.string.photo_error_desc,
                R.string.perm_denied_try_again,
            ) { viewModel.refresh(force = true); mediaAdapter?.refresh() }
            else -> showState(
                R.drawable.ic_no_photography, R.string.perm_denied_title, R.string.photo_permission_desc,
                R.string.photo_allow_access,
            ) { requestAccess() }
        }
    }

    private fun showState(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes desc: Int,
        @StringRes action: Int,
        onAction: () -> Unit,
    ) {
        val b = binding ?: return
        b.ivStateIcon.setImageResource(icon)
        b.tvStateTitle.setText(title)
        b.tvStateDesc.setText(desc)
        b.btnState.setText(action)
        b.btnState.setOnClickListener { onAction() }
    }

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        } catch (e: Exception) {
            // No camera app available: nothing to open.
        }
    }
}
