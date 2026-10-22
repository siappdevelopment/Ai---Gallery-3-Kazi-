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
import androidx.lifecycle.Observer
import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.activity.AlbumDetailActivity
import com.example.thirdgallery.activity.HomeActivity
import com.example.thirdgallery.database.AlbumRow
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.adapter.FilterEmptyAdapter
import com.example.thirdgallery.adapter.GalleryHeaderAdapter
import com.example.thirdgallery.adapter.MediaPagingAdapter
import com.example.thirdgallery.adapter.MonthAdapter
import com.example.thirdgallery.adapter.JournalPagingAdapter
import com.example.thirdgallery.adapter.MosaicPagingAdapter
import com.example.thirdgallery.adapter.MosaicTopAdapter
import com.example.thirdgallery.adapter.TimelineHeaderAdapter
import com.example.thirdgallery.adapter.YearAdapter
import com.example.thirdgallery.adapter.YearGridDecoration
import com.example.thirdgallery.databinding.FragmentPhotoBinding
import com.example.thirdgallery.helper.AlbumIcons
import com.example.thirdgallery.helper.GalleryLayoutPrefs
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.GalleryLayout
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.database.JournalDayRow
import com.example.thirdgallery.helper.ShareHelper
import com.example.thirdgallery.model.MosaicUi
import com.example.thirdgallery.model.QuickChip
import com.example.thirdgallery.model.TimelineZoom
import com.example.thirdgallery.viewmodel.GalleryLayoutViewModel
import com.example.thirdgallery.viewmodel.PhotoViewModel

/**
 * Gallery tab: every photo and video on the device, paged from Room.
 * Layouts: Classic (library + filter + day grid), Timeline (chips + Years / Months / Days) and
 * Mosaic (library cards + filter + a hero block and mixed tile sizes per day) and Journal (library
 * cards + filter + one card per day with Add to album / Share).
 * Switching layout or zoom only swaps the adapters around the same data; nothing is reloaded.
 */
class PhotoFragment : Fragment() {

    private enum class UiState { CONTENT, LOADING, EMPTY, FILTER_EMPTY, ERROR, PERMISSION }

    private enum class Mode { CLASSIC, DAYS, MONTHS, YEARS, MOSAIC, JOURNAL }

    private var _binding: FragmentPhotoBinding? = null
    private val binding get() = _binding

    private val viewModel: PhotoViewModel by viewModels()
    private val layoutViewModel: GalleryLayoutViewModel by activityViewModels()

    private var mediaAdapter: MediaPagingAdapter? = null
    private var classicHeader: GalleryHeaderAdapter? = null
    private var timelineHeader: TimelineHeaderAdapter? = null
    private var filterEmptyAdapter: FilterEmptyAdapter? = null
    private var monthAdapter: MonthAdapter? = null
    private var yearAdapter: YearAdapter? = null
    private var classicConcat: ConcatAdapter? = null
    private var daysConcat: ConcatAdapter? = null
    private var monthsConcat: ConcatAdapter? = null
    private var yearsConcat: ConcatAdapter? = null
    private var mosaicAdapter: MosaicPagingAdapter? = null
    private var mosaicTop: MosaicTopAdapter? = null
    private var mosaicConcat: ConcatAdapter? = null
    private var mosaicObserver: Observer<PagingData<MosaicUi>>? = null
    private var journalAdapter: JournalPagingAdapter? = null
    private var journalTop: MosaicTopAdapter? = null
    private var lastAlbumOpenAt = 0L
    private var journalConcat: ConcatAdapter? = null
    private var journalObserver: Observer<PagingData<JournalDayRow>>? = null
    private var journalRefreshState: LoadState? = null
    private var gridManager: GridLayoutManager? = null

    private var mode: Mode? = null
    private var refreshState: LoadState? = null
    private var mosaicRefreshState: LoadState? = null
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
        val density = resources.displayMetrics.density

        val columns = layoutViewModel.columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS
        val media = MediaPagingAdapter(tilePx(columns))
        val classic = GalleryHeaderAdapter(
            onFilterSelected = { viewModel.setFilter(it) },
            onAllAlbums = { (activity as? HomeActivity)?.openAlbumsTab() },
            onOpenAlbum = ::openAlbum,
        )
        val timeline = TimelineHeaderAdapter(
            onZoomSelected = { layoutViewModel.setZoom(it) },
            onZoomIn = { layoutViewModel.zoomIn() },
            onZoomOut = { layoutViewModel.zoomOut() },
            onChipClick = { open(it.kind, it.id, it.label) },
        )
        val filterEmpty = FilterEmptyAdapter()
        // A month opens its days, a year opens its months (same as the design).
        val months = MonthAdapter { layoutViewModel.setZoom(TimelineZoom.DAYS) }
        val years = YearAdapter { layoutViewModel.setZoom(TimelineZoom.MONTHS) }
        val mosaicTopAdapter = MosaicTopAdapter(
            onFilterSelected = { viewModel.setFilter(it) },
            onAllAlbums = { (activity as? HomeActivity)?.openAlbumsTab() },
            onOpenAlbum = ::openAlbum,
        )
        val mosaic = MosaicPagingAdapter(resources.displayMetrics.widthPixels)
        val journalTopAdapter = MosaicTopAdapter(
            onFilterSelected = { viewModel.setFilter(it) },
            onAllAlbums = { (activity as? HomeActivity)?.openAlbumsTab() },
            onOpenAlbum = ::openAlbum,
            compactCards = true,
        )
        val journal = JournalPagingAdapter(
            onAddToAlbum = { showAddToAlbum(it) },
            onShare = { viewModel.requestShare(it.dayKey) },
        )
        journalAdapter = journal
        journalTop = journalTopAdapter
        mosaicAdapter = mosaic
        mosaicTop = mosaicTopAdapter
        mediaAdapter = media
        classicHeader = classic
        timelineHeader = timeline
        filterEmptyAdapter = filterEmpty
        monthAdapter = months
        yearAdapter = years

        classicConcat = ConcatAdapter(classic, media, filterEmpty)
        daysConcat = ConcatAdapter(timeline, media, filterEmpty)
        monthsConcat = ConcatAdapter(timeline, months)
        yearsConcat = ConcatAdapter(timeline, years)
        mosaicConcat = ConcatAdapter(mosaicTopAdapter, mosaic, filterEmpty)
        journalConcat = ConcatAdapter(journalTopAdapter, journal, filterEmpty)

        val manager = GridLayoutManager(requireContext(), columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val headerCount = timeline.itemCount + classic.itemCount + mosaicTopAdapter.itemCount + journalTopAdapter.itemCount
                if (position < headerCount) return manager.spanCount
                return when (mode) {
                    Mode.CLASSIC, Mode.DAYS -> {
                        val index = position - headerCount
                        if (index >= media.itemCount || media.isHeader(index)) manager.spanCount else 1
                    }
                    Mode.MOSAIC -> {
                        val index = position - headerCount
                        if (index >= mosaic.itemCount) manager.spanCount else mosaic.spanAt(index, manager.spanCount)
                    }
                    else -> 1
                }
            }
        }
        gridManager = manager
        b.rvMedia.layoutManager = manager
        b.rvMedia.itemAnimator = null
        b.rvMedia.setHasFixedSize(true)
        b.rvMedia.setItemViewCacheSize(12)
        b.rvMedia.addItemDecoration(YearGridDecoration((7 * density).toInt()))

        media.addLoadStateListener { states ->
            refreshState = states.refresh
            render()
        }
        mosaic.addLoadStateListener { states ->
            mosaicRefreshState = states.refresh
            render()
        }
        journal.addLoadStateListener { states ->
            journalRefreshState = states.refresh
            render()
        }

        val owner = viewLifecycleOwner
        viewModel.media.observe(owner) { data -> media.submitData(owner.lifecycle, data) }
        viewModel.dayCounts.observe(owner) {
            media.updateCounts(it)
            mosaic.updateCounts(it)
        }
        viewModel.months.observe(owner) {
            months.submitList(it.orEmpty())
            render()
        }
        viewModel.years.observe(owner) {
            years.submitList(it.orEmpty())
            render()
        }
        viewModel.filter.observe(owner) {
            classic.setFilter(it)
            mosaicTopAdapter.setFilter(it)
            journalTopAdapter.setFilter(it)
            render()
        }
        viewModel.libraryAlbums.observe(owner) {
            classic.setContent(viewModel.totalCount.value ?: 0, it.orEmpty())
            mosaicTopAdapter.setAlbums(it.orEmpty())
            journalTopAdapter.setAlbums(it.orEmpty())
            updateChips()
        }
        viewModel.totalCount.observe(owner) { classic.setContent(it ?: 0, viewModel.libraryAlbums.value.orEmpty()) }
        viewModel.videoCount.observe(owner) { updateChips() }
        viewModel.favoriteCount.observe(owner) { updateChips() }
        viewModel.shareRequest.observe(owner) { event ->
            event.getContentIfNotHandled()?.let { uids ->
                val host = activity ?: return@let
                ShareHelper.share(host, uids) {
                    android.widget.Toast.makeText(host.applicationContext, R.string.photo_share_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewModel.syncing.observe(owner) { render() }
        viewModel.synced.observe(owner) { render() }
        viewModel.syncError.observe(owner) { render() }

        layoutViewModel.layout.observe(owner) { layout ->
            // The Timeline has no filter bar, so it always shows everything.
            if (layout == GalleryLayout.TIMELINE) viewModel.setFilter(MediaFilter.ALL)
            applyMode()
        }
        layoutViewModel.zoom.observe(owner) { applyMode() }
        layoutViewModel.columns.observe(owner) { applyMode() }

        hasAccess = MediaPermissionHelper.hasAccess(requireContext())
        applyMode()
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
        stopMosaicData()
        stopJournalData()
        journalAdapter = null
        journalTop = null
        journalConcat = null
        journalRefreshState = null
        mediaAdapter = null
        mosaicAdapter = null
        mosaicTop = null
        mosaicConcat = null
        mosaicRefreshState = null
        classicHeader = null
        timelineHeader = null
        filterEmptyAdapter = null
        monthAdapter = null
        yearAdapter = null
        classicConcat = null
        daysConcat = null
        monthsConcat = null
        yearsConcat = null
        gridManager = null
        mode = null
        refreshState = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val MOSAIC_COLUMNS = 3
        const val ALBUM_OPEN_GAP_MS = 600L
        const val JOURNAL_COLUMNS = 1
    }

    private fun tilePx(columns: Int): Int =
        (resources.displayMetrics.widthPixels / columns.coerceAtLeast(1)).coerceAtLeast(1)

    private fun currentMode(): Mode {
        when (layoutViewModel.layout.value) {
            GalleryLayout.MOSAIC -> return Mode.MOSAIC
            GalleryLayout.JOURNAL -> return Mode.JOURNAL
            GalleryLayout.TIMELINE -> Unit
            else -> return Mode.CLASSIC
        }
        return when (layoutViewModel.zoom.value) {
            TimelineZoom.YEARS -> Mode.YEARS
            TimelineZoom.MONTHS -> Mode.MONTHS
            else -> Mode.DAYS
        }
    }

    /** Applies layout / zoom / columns: swaps adapters and the column count, never the data. */
    private fun applyMode() {
        val b = binding ?: return
        val manager = gridManager ?: return
        val columns = layoutViewModel.columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS
        val zoom = layoutViewModel.zoom.value ?: TimelineZoom.DAYS
        val newMode = currentMode()

        mediaAdapter?.setThumbSize(tilePx(columns))
        timelineHeader?.setState(
            zoom = zoom,
            chips = buildChips(),
            canZoomIn = columns > GalleryLayoutPrefs.MIN_COLUMNS,
            canZoomOut = columns < GalleryLayoutPrefs.MAX_COLUMNS,
        )

        val spanCount = when (newMode) {
            Mode.CLASSIC, Mode.DAYS -> columns
            Mode.MONTHS -> 1
            Mode.YEARS -> 2
            Mode.MOSAIC -> MOSAIC_COLUMNS
            Mode.JOURNAL -> JOURNAL_COLUMNS
        }
        if (manager.spanCount != spanCount) manager.spanCount = spanCount

        if (newMode != mode) {
            mode = newMode
            // Mosaic pages are only loaded while the Mosaic layout is shown.
            if (newMode == Mode.MOSAIC) startMosaicData() else stopMosaicData()
            if (newMode == Mode.JOURNAL) startJournalData() else stopJournalData()
            val target = when (newMode) {
                Mode.CLASSIC -> classicConcat
                Mode.DAYS -> daysConcat
                Mode.MONTHS -> monthsConcat
                Mode.YEARS -> yearsConcat
                Mode.MOSAIC -> mosaicConcat
                Mode.JOURNAL -> journalConcat
            }
            if (target != null) {
                // setAdapter (not swapAdapter): each ConcatAdapter numbers its own view types from 0,
                // so the old recycled rows must not be reused. swapAdapter keeps the recycler pool,
                // which handed a Classic header row to the Timeline header and crashed on binding.
                b.rvMedia.setAdapter(target)
                b.rvMedia.scrollToPosition(0)
            }
        }
        render()
    }

    private fun startMosaicData() {
        if (mosaicObserver != null || _binding == null) return
        val owner = viewLifecycleOwner
        val observer = Observer<PagingData<MosaicUi>> { data -> mosaicAdapter?.submitData(owner.lifecycle, data) }
        mosaicObserver = observer
        viewModel.mosaicMedia.observe(owner, observer)
    }

    private fun startJournalData() {
        if (journalObserver != null || _binding == null) return
        val owner = viewLifecycleOwner
        val observer = Observer<PagingData<JournalDayRow>> { data -> journalAdapter?.submitData(owner.lifecycle, data) }
        journalObserver = observer
        viewModel.journalMedia.observe(owner, observer)
    }

    private fun stopJournalData() {
        val observer = journalObserver ?: return
        journalObserver = null
        viewModel.journalMedia.removeObserver(observer)
    }

    /** Opens a library folder card in the album screen; ignores empty rows and double taps. */
    private fun openAlbum(album: AlbumRow) = open(AlbumKind.DEVICE, album.bucketId, album.bucketName)

    private fun open(kind: AlbumKind, id: Long, name: String) {
        val context = context ?: return
        if (name.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAlbumOpenAt < ALBUM_OPEN_GAP_MS) return
        lastAlbumOpenAt = now
        startActivity(AlbumDetailActivity.intent(context, kind, id, name))
    }

    /** Opens the "Add to album" sheet for every item of the row's day (under the active filter). */
    private fun showAddToAlbum(row: JournalDayRow) {
        val host = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        val fm = host.supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(AddToAlbumBottomSheet.TAG) != null) return
        val filter = viewModel.filter.value ?: MediaFilter.ALL
        AddToAlbumBottomSheet.newInstance(row.dayKey, filter, row.total).show(fm, AddToAlbumBottomSheet.TAG)
    }

    private fun stopMosaicData() {
        val observer = mosaicObserver ?: return
        mosaicObserver = null
        viewModel.mosaicMedia.removeObserver(observer)
    }

    private fun buildChips(): List<QuickChip> {
        val chips = ArrayList<QuickChip>()
        viewModel.libraryAlbums.value.orEmpty().forEach {
            chips.add(QuickChip(AlbumIcons.forName(it.bucketName), it.bucketName, it.total, AlbumKind.DEVICE, it.bucketId))
        }
        chips.add(QuickChip(R.drawable.ic_smart_display, getString(R.string.photo_chip_videos), viewModel.videoCount.value ?: 0, AlbumKind.VIDEOS))
        chips.add(QuickChip(R.drawable.ic_favorite, getString(R.string.photo_chip_favorites), viewModel.favoriteCount.value ?: 0, AlbumKind.FAVORITES))
        return chips
    }

    private fun updateChips() {
        val zoom = layoutViewModel.zoom.value ?: TimelineZoom.DAYS
        val columns = layoutViewModel.columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS
        timelineHeader?.setState(
            zoom, buildChips(),
            columns > GalleryLayoutPrefs.MIN_COLUMNS, columns < GalleryLayoutPrefs.MAX_COLUMNS,
        )
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
        val timelineCards = when (mode) {
            Mode.MONTHS -> monthAdapter?.itemCount ?: 0
            Mode.YEARS -> yearAdapter?.itemCount ?: 0
            Mode.MOSAIC, Mode.JOURNAL -> -2
            else -> -1
        }
        val itemCount = when {
            timelineCards >= 0 -> timelineCards
            mode == Mode.MOSAIC -> mosaicAdapter?.itemCount ?: 0
            mode == Mode.JOURNAL -> journalAdapter?.itemCount ?: 0
            else -> mediaAdapter?.itemCount ?: 0
        }
        val pagedMode = timelineCards < 0
        val activeRefresh = when (mode) {
            Mode.MOSAIC -> mosaicRefreshState
            Mode.JOURNAL -> journalRefreshState
            else -> refreshState
        }
        val synced = viewModel.synced.value == true
        val filter = viewModel.filter.value ?: MediaFilter.ALL
        return when {
            !hasAccess -> UiState.PERMISSION
            itemCount > 0 -> UiState.CONTENT
            (pagedMode && activeRefresh is LoadState.Error) || viewModel.syncError.value == true -> UiState.ERROR
            viewModel.syncing.value == true || !synced || (pagedMode && activeRefresh is LoadState.Loading) -> UiState.LOADING
            filter == MediaFilter.ALL -> UiState.EMPTY
            else -> UiState.FILTER_EMPTY
        }
    }

    private fun render() {
        val b = binding ?: return
        val state = currentState()
        // Classic and Mosaic have the filter bar; Timeline has its own header.
        val inClassic = mode == Mode.CLASSIC || mode == Mode.MOSAIC || mode == Mode.JOURNAL

        // The grid stays visible when a filter matches nothing, so the filter bar stays usable.
        val listVisible = state == UiState.CONTENT || (state == UiState.FILTER_EMPTY && inClassic)
        b.rvMedia.visibility = if (listVisible) View.VISIBLE else View.INVISIBLE
        classicHeader?.setVisible(mode == Mode.CLASSIC && listVisible)
        mosaicTop?.setVisible(mode == Mode.MOSAIC && listVisible)
        journalTop?.setVisible(mode == Mode.JOURNAL && listVisible)
        timelineHeader?.setVisible(!inClassic && listVisible)
        filterEmptyAdapter?.setVisible(state == UiState.FILTER_EMPTY && inClassic)

        b.progress.visibility = if (state == UiState.LOADING) View.VISIBLE else View.GONE
        val showState = state == UiState.EMPTY || state == UiState.ERROR || state == UiState.PERMISSION ||
            (state == UiState.FILTER_EMPTY && !inClassic)
        b.stateGroup.visibility = if (showState) View.VISIBLE else View.GONE
        if (!showState) return

        when (state) {
            UiState.ERROR -> showState(
                R.drawable.ic_error, R.string.photo_error_title, R.string.photo_error_desc,
                R.string.perm_denied_try_again,
            ) { viewModel.refresh(force = true); mediaAdapter?.refresh(); mosaicAdapter?.refresh(); journalAdapter?.refresh() }
            UiState.PERMISSION -> showState(
                R.drawable.ic_no_photography, R.string.perm_denied_title, R.string.photo_permission_desc,
                R.string.photo_allow_access,
            ) { requestAccess() }
            else -> showState(
                R.drawable.ic_photo_library, R.string.photo_empty_title, R.string.photo_empty_desc,
                R.string.photo_open_camera,
            ) { openCamera() }
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
