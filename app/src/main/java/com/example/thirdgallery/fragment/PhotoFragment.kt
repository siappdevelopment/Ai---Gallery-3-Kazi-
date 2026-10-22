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
import androidx.fragment.app.viewModels
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.databinding.FragmentPhotoBinding
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.viewmodel.PhotoViewModel

/** Gallery tab: every photo and video on the device, grouped by day, paged from Room. */
class PhotoFragment : Fragment() {

    private enum class UiState { CONTENT, LOADING, EMPTY, FILTER_EMPTY, ERROR, PERMISSION }

    private var _binding: FragmentPhotoBinding? = null
    private val binding get() = _binding

    private val viewModel: PhotoViewModel by viewModels()

    private var adapter: MediaPagingAdapter? = null
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
        val mediaAdapter = MediaPagingAdapter(thumbPx)
        adapter = mediaAdapter

        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (mediaAdapter.isHeader(position)) spanCount else 1
        }
        b.rvMedia.layoutManager = layoutManager
        b.rvMedia.adapter = mediaAdapter
        b.rvMedia.itemAnimator = null
        b.rvMedia.setHasFixedSize(true)
        b.rvMedia.setItemViewCacheSize(spanCount * 3)
        b.rvMedia.recycledViewPool.setMaxRecycledViews(MediaPagingAdapter.TYPE_ITEM, spanCount * 12)

        b.tabAll.setOnClickListener { viewModel.setFilter(MediaFilter.ALL) }
        b.tabPhotos.setOnClickListener { viewModel.setFilter(MediaFilter.PHOTOS) }
        b.tabVideos.setOnClickListener { viewModel.setFilter(MediaFilter.VIDEOS) }
        b.tabFavorites.setOnClickListener { viewModel.setFilter(MediaFilter.FAVORITES) }

        mediaAdapter.addLoadStateListener { states ->
            refreshState = states.refresh
            render()
        }

        val owner = viewLifecycleOwner
        viewModel.media.observe(owner) { data -> mediaAdapter.submitData(owner.lifecycle, data) }
        viewModel.dayCounts.observe(owner) { mediaAdapter.updateCounts(it) }
        viewModel.filter.observe(owner) {
            updateFilterTabs(it)
            render()
        }
        viewModel.syncing.observe(owner) { render() }
        viewModel.synced.observe(owner) { render() }
        viewModel.syncError.observe(owner) { render() }

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
        adapter = null
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

    private fun updateFilterTabs(filter: MediaFilter) {
        val b = binding ?: return
        b.tabAll.isSelected = filter == MediaFilter.ALL
        b.tabPhotos.isSelected = filter == MediaFilter.PHOTOS
        b.tabVideos.isSelected = filter == MediaFilter.VIDEOS
        b.tabFavorites.isSelected = filter == MediaFilter.FAVORITES
    }

    private fun currentState(): UiState {
        val itemCount = adapter?.itemCount ?: 0
        val synced = viewModel.synced.value == true
        return when {
            !hasAccess -> UiState.PERMISSION
            itemCount > 0 -> UiState.CONTENT
            refreshState is LoadState.Error || viewModel.syncError.value == true -> UiState.ERROR
            viewModel.syncing.value == true || !synced || refreshState is LoadState.Loading -> UiState.LOADING
            viewModel.filter.value == MediaFilter.ALL || viewModel.filter.value == null -> UiState.EMPTY
            else -> UiState.FILTER_EMPTY
        }
    }

    private fun render() {
        val b = binding ?: return
        val state = currentState()
        b.rvMedia.visibility = if (state == UiState.CONTENT) View.VISIBLE else View.INVISIBLE
        b.progress.visibility = if (state == UiState.LOADING) View.VISIBLE else View.GONE
        val showState = state != UiState.CONTENT && state != UiState.LOADING
        b.stateGroup.visibility = if (showState) View.VISIBLE else View.GONE
        if (!showState) return

        when (state) {
            UiState.EMPTY -> showState(
                R.drawable.ic_photo_library, R.string.photo_empty_title, R.string.photo_empty_desc,
                R.string.photo_open_camera,
            ) { openCamera() }
            UiState.FILTER_EMPTY -> showState(
                R.drawable.ic_favorite, R.string.photo_filter_empty_title, R.string.photo_filter_empty_desc, null,
            ) {}
            UiState.ERROR -> showState(
                R.drawable.ic_error, R.string.photo_error_title, R.string.photo_error_desc,
                R.string.perm_denied_try_again,
            ) { viewModel.refresh(force = true); adapter?.refresh() }
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
        @StringRes action: Int?,
        onAction: () -> Unit,
    ) {
        val b = binding ?: return
        b.ivStateIcon.setImageResource(icon)
        b.tvStateTitle.setText(title)
        b.tvStateDesc.setText(desc)
        if (action == null) {
            b.btnState.visibility = View.GONE
        } else {
            b.btnState.visibility = View.VISIBLE
            b.btnState.setText(action)
            b.btnState.setOnClickListener { onAction() }
        }
    }

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        } catch (e: Exception) {
            // No camera app available: nothing to open.
        }
    }
}
