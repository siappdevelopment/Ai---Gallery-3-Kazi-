package com.photovideo.gallerykz.memories.fragment

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
import androidx.recyclerview.widget.RecyclerView
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.ads.AdScreen
import com.photovideo.gallerykz.memories.ads.AdSlot
import com.photovideo.gallerykz.memories.ads.SlotSpec
import com.photovideo.gallerykz.memories.adapter.AdRowAdapter
import com.photovideo.gallerykz.memories.activity.AlbumDetailActivity
import com.photovideo.gallerykz.memories.helper.SelectionBarController
import com.photovideo.gallerykz.memories.viewmodel.SelectionViewModel
import com.photovideo.gallerykz.memories.activity.FullScreenMediaActivity
import com.photovideo.gallerykz.memories.database.AlbumRow
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.adapter.FilterEmptyAdapter
import com.photovideo.gallerykz.memories.adapter.GalleryHeaderAdapter
import com.photovideo.gallerykz.memories.adapter.MediaPagingAdapter
import com.photovideo.gallerykz.memories.adapter.MonthAdapter
import com.photovideo.gallerykz.memories.adapter.JournalPagingAdapter
import com.photovideo.gallerykz.memories.adapter.MosaicPagingAdapter
import com.photovideo.gallerykz.memories.adapter.MosaicTopAdapter
import com.photovideo.gallerykz.memories.adapter.TimelineHeaderAdapter
import com.photovideo.gallerykz.memories.adapter.YearAdapter
import com.photovideo.gallerykz.memories.adapter.YearGridDecoration
import com.photovideo.gallerykz.memories.databinding.FragmentPhotoBinding
import com.photovideo.gallerykz.memories.helper.AlbumIcons
import com.photovideo.gallerykz.memories.helper.GalleryLayoutPrefs
import com.photovideo.gallerykz.memories.helper.MediaFormat
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.model.GalleryLayout
import com.photovideo.gallerykz.memories.model.GallerySort
import com.photovideo.gallerykz.memories.model.MediaFilter
import com.photovideo.gallerykz.memories.database.JournalDayRow
import com.photovideo.gallerykz.memories.helper.ShareHelper
import com.photovideo.gallerykz.memories.model.MosaicUi
import com.photovideo.gallerykz.memories.model.QuickChip
import com.photovideo.gallerykz.memories.model.TimelineZoom
import com.photovideo.gallerykz.memories.viewmodel.GalleryLayoutViewModel
import com.photovideo.gallerykz.memories.viewmodel.PhotoViewModel

/**
 * Gallery tab: every photo and video on the device, paged from Room.
 * Layouts: Classic (library + filter + day grid), Timeline (chips + Years / Months / Days) and
 * Mosaic (library cards + filter + a hero block and mixed tile sizes per day) and Journal (library
 * cards + filter + one card per day with Add to album / Share).
 * Switching layout or zoom only swaps the adapters around the same data; nothing is reloaded.
 */
class PhotoFragment : Fragment() {

    private enum class UiState { CONTENT, LOADING, EMPTY, FILTER_EMPTY, ERROR, PERMISSION, SWITCHING }

    private enum class Mode { CLASSIC, DAYS, MONTHS, YEARS, MOSAIC, JOURNAL }

    private var _binding: FragmentPhotoBinding? = null
    private val binding get() = _binding

    private val viewModel: PhotoViewModel by viewModels()
    private val layoutViewModel: GalleryLayoutViewModel by activityViewModels()
    private val selectionViewModel: SelectionViewModel by activityViewModels()
    private var selectionBars: SelectionBarController? = null
    private var lastFilter: MediaFilter? = null
    private var lastSort: GallerySort? = null
    private var lastLayout: GalleryLayout? = null

    private val selectionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        selectionViewModel.onRequestResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

    /** The vault setup opened by Hide when the vault has no PIN yet; a finished setup continues with Hide. */
    private val vaultSetupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        selectionViewModel.onVaultSetupResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

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
    private var lastRecapAt = 0L
    private var journalConcat: ConcatAdapter? = null
    private var journalObserver: Observer<PagingData<JournalDayRow>>? = null
    private var journalRefreshState: LoadState? = null
    private var gridManager: GridLayoutManager? = null

    // The Gallery ad sits inside the grid, in the row after the first row of media (one ad row, whatever the layout).
    private var adSlot: AdSlot? = null
    private var adStarted = false
    private var adLeft = false
    private var gridAd: AdRowAdapter? = null
    private var mosaicAd: AdRowAdapter? = null
    private var journalAd: AdRowAdapter? = null

    private var mode: Mode? = null
    private var refreshState: LoadState? = null
    private var mosaicRefreshState: LoadState? = null
    private var hasAccess = false
    private var requesting = false
    private var emptyRetries = 0
    private var emptyRetryPending = false

    /**
     * A filter tab (All / Photo / Video / Favorite) was tapped and the list of the new filter has not finished loading yet. The
     * old list is cleared at once and the header with the tabs stays on screen, so the tab never shows another filter's items or
     * empty message, and the tabs never blink out. [switchSawLoading] tells the load that belongs to the new filter from the
     * instant "empty" the clearing itself reports.
     */
    private var switchingFilter = false
    private var switchSawLoading = false
    private val endFilterSwitch = Runnable { finishFilterSwitch() }

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

        val host = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        val bars = SelectionBarController(
            bars = b.selectionBars,
            activity = host,
            owner = viewLifecycleOwner,
            viewModel = selectionViewModel,
            sourceAlbumId = { 0L },
            launch = { selectionLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(it.intentSender).build()) },
            launchVaultSetup = { vaultSetupLauncher.launch(it) },
            onSelectionChanged = {
                mediaAdapter?.refreshSelection(b.rvMedia)
                mosaicAdapter?.refreshSelection()
            },
            onActiveChanged = { active -> setSelectionChrome(active) },
        )
        selectionBars = bars

        val columns = layoutViewModel.columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS
        val media = MediaPagingAdapter(tilePx(columns), ::openMedia, bars.selection)
        val classic = GalleryHeaderAdapter(
            onFilterSelected = { selectFilter(it) },
            onAllAlbums = { galleryHost()?.openAlbumsTab() },
            onOpenAlbum = ::openAlbum,
            onSortClick = { GallerySortBottomSheet.show(childFragmentManager, viewModel.sort.value ?: GallerySort.DATE_NEWEST) },
        )
        childFragmentManager.setFragmentResultListener(GallerySortBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            GallerySort.entries.getOrNull(result.getInt(GallerySortBottomSheet.RESULT_SORT, -1))?.let { viewModel.setSort(it) }
        }
        val timeline = TimelineHeaderAdapter(
            onZoomSelected = { layoutViewModel.setZoom(it) },
            onZoomIn = { layoutViewModel.zoomIn() },
            onZoomOut = { layoutViewModel.zoomOut() },
            onChipClick = { open(it.kind, it.id, it.label) },
        )
        val filterEmpty = FilterEmptyAdapter()
        // A month opens its days, a year opens its months (same as the design).
        val months = MonthAdapter { row ->
            open(AlbumKind.MONTH, row.monthKey, MediaFormat.monthTitle(requireContext(), row.monthKey))
        }
        val years = YearAdapter { row ->
            open(AlbumKind.YEAR, row.year.toLong(), MediaFormat.yearTitle(requireContext(), row.year))
        }
        val mosaicTopAdapter = MosaicTopAdapter(
            onFilterSelected = { selectFilter(it) },
            onAllAlbums = { galleryHost()?.openAlbumsTab() },
            onOpenAlbum = ::openAlbum,
        )
        val mosaic = MosaicPagingAdapter(resources.displayMetrics.widthPixels, ::openMedia, bars.selection)
        val journalTopAdapter = MosaicTopAdapter(
            onFilterSelected = { selectFilter(it) },
            onAllAlbums = { galleryHost()?.openAlbumsTab() },
            onOpenAlbum = ::openAlbum,
            compactCards = true,
        )
        val journal = JournalPagingAdapter(
            onAddToAlbum = { showAddToAlbum(it) },
            onShare = { viewModel.requestShare(it.dayKey) },
            onRecap = { recap(it) },
            onOpen = ::openMedia,
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

        // The ad lives in one container for the whole screen; the row of the active layout shows it.
        val adContainer = android.widget.FrameLayout(requireContext())
        @Suppress("UNCHECKED_CAST")
        val journalInner = journal as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val gridRow = AdRowAdapter(media, adContainer) { gridAdIndex() }
        val mosaicRow = AdRowAdapter(mosaic, adContainer) { mosaicAdIndex() }
        val journalRow = AdRowAdapter(journalInner, adContainer) { journalAdIndex() }
        gridAd = gridRow
        mosaicAd = mosaicRow
        journalAd = journalRow
        adSlot = AdSlot(
            requireActivity(), adContainer, viewLifecycleOwner, adSpec(),
            onVisibility = { visible ->
                gridRow.setEnabled(visible)
                mosaicRow.setEnabled(visible)
                journalRow.setEnabled(visible)
            },
            liveSpec = { adSpec() },
        )

        classicConcat = ConcatAdapter(classic, gridRow, filterEmpty)
        daysConcat = ConcatAdapter(timeline, gridRow, filterEmpty)
        monthsConcat = ConcatAdapter(timeline, months)
        yearsConcat = ConcatAdapter(timeline, years)
        mosaicConcat = ConcatAdapter(mosaicTopAdapter, mosaicRow, filterEmpty)
        journalConcat = ConcatAdapter(journalTopAdapter, journalRow, filterEmpty)

        val manager = GridLayoutManager(requireContext(), columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val headerCount = timeline.itemCount + classic.itemCount + mosaicTopAdapter.itemCount + journalTopAdapter.itemCount
                if (position < headerCount) return manager.spanCount
                return when (mode) {
                    Mode.CLASSIC, Mode.DAYS -> {
                        val row = position - headerCount
                        val index = gridRow.innerIndex(row)
                        if (gridRow.isAd(row) || index >= media.itemCount || media.isHeader(index)) manager.spanCount else 1
                    }
                    Mode.MOSAIC -> {
                        val row = position - headerCount
                        val index = mosaicRow.innerIndex(row)
                        if (mosaicRow.isAd(row) || index >= mosaic.itemCount) manager.spanCount else mosaic.spanAt(index, manager.spanCount)
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
            trackFilterSwitch(states.refresh)
            render()
        }
        mosaic.addLoadStateListener { states ->
            mosaicRefreshState = states.refresh
            trackFilterSwitch(states.refresh)
            render()
        }
        journal.addLoadStateListener { states ->
            journalRefreshState = states.refresh
            trackFilterSwitch(states.refresh)
            render()
        }

        val owner = viewLifecycleOwner
        viewModel.media.observe(owner) { data -> media.submitData(owner.lifecycle, data) }
        viewModel.sort.observe(owner) { sort ->
            classic.setSortLabel(getString(GallerySortBottomSheet.labelOf(sort)))
            // A new order starts at the top of the grid.
            if (lastSort != null && lastSort != sort) b.rvMedia.scrollToPosition(0)
            lastSort = sort
        }
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
            selectionViewModel.setSource(AlbumKind.DEVICE, 0L, it)
            if (lastFilter != null && lastFilter != it) selectionViewModel.clear()
            lastFilter = it
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
        viewModel.recapRequest.observe(owner) { event ->
            event.getContentIfNotHandled()?.let { uids ->
                val host = activity ?: return@let
                if (uids.isEmpty()) {
                    android.widget.Toast.makeText(host.applicationContext, R.string.photo_filter_empty_title, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(com.photovideo.gallerykz.memories.activity.HighlightsStartActivity.intent(host, uids))
                }
            }
        }
        viewModel.syncing.observe(owner) { render() }
        viewModel.synced.observe(owner) { render() }
        viewModel.syncError.observe(owner) { render() }

        layoutViewModel.layout.observe(owner) { layout ->
            if (lastLayout != null && lastLayout != layout) selectionViewModel.clear()
            lastLayout = layout
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
        enterAd()
        // Permission may have been granted or revoked in Settings while the app was in the background.
        syncAccess()
    }

    override fun onPause() {
        adLeft = true
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Another tab was opened: leave selection mode.
        if (hidden) {
            selectionViewModel.clear()
            adLeft = true
        } else {
            enterAd()
        }
    }

    /**
     * The Gallery ad is requested when this tab is really on screen (the first time it is resumed and shown), not while it waits next to
     * Home. With `Gallery_Ad_Auto_Refresh` on, the timer of the slot refreshes it; with it off, a new ad is requested only when the user
     * comes back here after another screen (an activity, another tab or page).
     */
    private fun enterAd() {
        if (!isResumed || isHidden) return
        val slot = adSlot ?: return
        if (!adStarted) {
            adStarted = true
            adLeft = false
            slot.start()
        } else if (adLeft) {
            adLeft = false
            // Only when the interval (Gallery_Ad_Auto_Second) has passed since the last request; back earlier, the ad stays.
            if (adSpec().refreshSeconds == 0) slot.refreshNow(AdRemoteConfig.config.screen(AdScreen.GALLERY).autoSeconds)
        }
    }

    private fun adSpec(): SlotSpec {
        val screen = AdRemoteConfig.config.screen(AdScreen.GALLERY)
        return SlotSpec(
            show = screen.show && screen.nativeId.isNotEmpty(), native = true, bannerId = "", nativeId = screen.nativeId,
            refreshSeconds = if (screen.autoRefresh) screen.autoSeconds else 0,
            preload = AdRemoteConfig.config.inter.nativePreload, cacheKey = "slot_gallery_grid", medium = true,
        )
    }

    /** Classic / Days: the ad goes after the first row of photos (before the next day header when the day is shorter). */
    private fun gridAdIndex(): Int {
        val media = mediaAdapter ?: return -1
        val n = media.itemCount
        if (n == 0) return -1
        val columns = layoutViewModel.columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS
        var i = if (media.isHeader(0)) 1 else 0
        var count = 0
        while (i < n && count < columns) {
            if (media.isHeader(i)) return i
            i++
            count++
        }
        return if (count == columns) i else -1
    }

    /** Mosaic: the ad goes after the first hero block of the first day. */
    private fun mosaicAdIndex(): Int {
        val mosaic = mosaicAdapter ?: return -1
        val n = mosaic.itemCount
        var hero = -1
        for (i in 0 until minOf(n, 4)) {
            if (mosaic.peek(i) is MosaicUi.Hero) {
                hero = i
                break
            }
        }
        if (hero < 0) return -1
        var i = hero + 1
        while (i < n && i < hero + 3 && mosaic.peek(i) is MosaicUi.Spacer) i++
        return i
    }

    /** Journal: one card per day, the ad goes after the first card. */
    private fun journalAdIndex(): Int = if ((journalAdapter?.itemCount ?: 0) >= 1) 1 else -1

    /** The Gallery page this tab lives in (inside the launcher or on its own). */
    private fun galleryHost(): GalleryHost? = (parentFragment as? GalleryHost) ?: (activity as? GalleryHost)

    override fun onDestroyView() {
        // The bars must come back even when the view goes away while selecting.
        galleryHost()?.setChromeVisible(true)
        binding?.root?.removeCallbacks(emptyRetry)
        binding?.root?.removeCallbacks(endFilterSwitch)
        emptyRetryPending = false
        switchingFilter = false
        switchSawLoading = false
        selectionBars = null
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
        adSlot = null
        adStarted = false
        adLeft = false
        gridAd = null
        mosaicAd = null
        journalAd = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val MOSAIC_COLUMNS = 3
        const val ALBUM_OPEN_GAP_MS = 600L
        const val RECAP_GAP_MS = 1_000L
        const val JOURNAL_COLUMNS = 1
        const val MAX_EMPTY_RETRIES = 3
        const val EMPTY_RETRY_DELAY_MS = 1_500L

        /** A filter switch never lasts longer than this, even if the load that should end it was not seen. */
        const val FILTER_SWITCH_TIMEOUT_MS = 3_000L
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
        // The ad row follows the new number of columns.
        gridAd?.sync()
        mosaicAd?.sync()
        journalAd?.sync()

        if (newMode != mode) {
            mode = newMode
            finishFilterSwitch()
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
        journalRefreshState = null
        viewModel.journalMedia.removeObserver(observer)
    }

    /** Opens a library folder card in the album screen; ignores empty rows and double taps. */
    /** Opens the tapped photo or video in the viewer, inside the gallery under the active filter. */
    private fun openMedia(uid: Long) {
        val context = context ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAlbumOpenAt < ALBUM_OPEN_GAP_MS) return
        lastAlbumOpenAt = now
        val filter = viewModel.filter.value ?: MediaFilter.ALL
        startActivity(FullScreenMediaActivity.intent(context, uid, AlbumKind.DEVICE, 0L, filter))
    }

    private fun openAlbum(album: AlbumRow) = open(AlbumKind.DEVICE, album.bucketId, album.bucketName)

    private fun open(kind: AlbumKind, id: Long, name: String) {
        val context = context ?: return
        if (name.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAlbumOpenAt < ALBUM_OPEN_GAP_MS) return
        lastAlbumOpenAt = now
        startActivity(AlbumDetailActivity.intent(context, kind, id, name))
    }

    /** Recap: starts the slideshow of the row's day at once (ignores a second tap while the first is being prepared). */
    private fun recap(row: JournalDayRow) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecapAt < RECAP_GAP_MS) return
        lastRecapAt = now
        viewModel.requestRecap(row.dayKey)
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
        // A stale "Loading" would keep the spinner on when this layout is opened again.
        mosaicRefreshState = null
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
        val classicMode = mode == Mode.CLASSIC || mode == Mode.MOSAIC || mode == Mode.JOURNAL
        return when {
            !hasAccess -> UiState.PERMISSION
            // Between the tap on a filter and the end of its first load: the header stays, the list area stays quiet.
            switchingFilter && classicMode -> UiState.SWITCHING
            itemCount > 0 -> UiState.CONTENT
            (pagedMode && activeRefresh is LoadState.Error) || viewModel.syncError.value == true -> UiState.ERROR
            viewModel.syncing.value == true || !synced || (pagedMode && activeRefresh is LoadState.Loading) -> UiState.LOADING
            filter == MediaFilter.ALL -> UiState.EMPTY
            else -> UiState.FILTER_EMPTY
        }
    }

    /**
     * Runs [change] (which resizes the list, e.g. the top and tab bars hiding while selecting) and then puts the first visible item
     * back where it was on the screen. Without it the taller list re-anchors itself and the photos jump to other places, most of
     * all when the selection starts at the bottom of the gallery.
     */
    private fun keepScrollPosition(change: () -> Unit) {
        val rv = binding?.rvMedia
        val manager = gridManager
        val position = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val anchor = if (position != RecyclerView.NO_POSITION) manager?.findViewByPosition(position) else null
        if (rv == null || anchor == null) {
            change()
            return
        }
        val before = IntArray(2)
        anchor.getLocationOnScreen(before)
        // The correction runs before the first frame with the new layout is drawn (a post() ran one frame later, so the grid
        // was seen jumping and coming back).
        rv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (rv.isLayoutRequested) return true
                if (rv.viewTreeObserver.isAlive) rv.viewTreeObserver.removeOnPreDrawListener(this)
                val view = gridManager?.findViewByPosition(position) ?: return true
                val after = IntArray(2)
                view.getLocationOnScreen(after)
                val shift = after[1] - before[1]
                if (shift != 0) rv.scrollBy(0, shift)
                return true
            }
        })
        change()
    }

    /**
     * Selection mode on / off: the top bar and tab bar go (or come back) and the grid becomes taller. The grid keeps the top
     * bar's height as padding while selecting, so at the top of the list nothing hides under the selection bar or jumps.
     */
    private fun setSelectionChrome(active: Boolean) {
        val rv = binding?.rvMedia ?: return
        val pad = if (active) galleryHost()?.topChromeHeight() ?: 0 else 0
        keepScrollPosition {
            if (rv.paddingTop != pad) rv.setPadding(rv.paddingLeft, pad, rv.paddingRight, rv.paddingBottom)
            galleryHost()?.setChromeVisible(!active)
        }
    }

    /** A filter tab was tapped: clear the old list first, then ask for the new filter, so the two never show together. */
    private fun selectFilter(filter: MediaFilter) {
        if (viewModel.filter.value == filter) return
        beginFilterSwitch()
        viewModel.setFilter(filter)
    }

    private fun beginFilterSwitch() {
        val b = binding ?: return
        switchingFilter = true
        switchSawLoading = false
        // The rows of the old filter go away in this very frame (the new data only arrives a frame or more later), and come back
        // together with the new ones when finishFilterSwitch runs.
        gridAd?.setHidden(true)
        mosaicAd?.setHidden(true)
        journalAd?.setHidden(true)
        b.root.removeCallbacks(endFilterSwitch)
        b.root.postDelayed(endFilterSwitch, FILTER_SWITCH_TIMEOUT_MS)
        render()
    }

    /** The first load of the new filter is over once a load was seen starting and then stopping. */
    private fun trackFilterSwitch(refresh: LoadState) {
        if (!switchingFilter) return
        if (refresh is LoadState.Loading) {
            switchSawLoading = true
        } else if (switchSawLoading && refresh is LoadState.NotLoading) {
            finishFilterSwitch()
        }
    }

    private fun finishFilterSwitch() {
        binding?.root?.removeCallbacks(endFilterSwitch)
        if (!switchingFilter) return
        switchingFilter = false
        switchSawLoading = false
        gridAd?.setHidden(false)
        mosaicAd?.setHidden(false)
        journalAd?.setHidden(false)
        render()
    }

    /**
     * Right after the permission is granted MediaStore can still answer with nothing, so the first sync finds no photos and
     * "No photos yet" would stay. While that is shown (unfiltered, access granted) the index is read again a few times.
     */
    private fun scheduleEmptyRetry(b: FragmentPhotoBinding, state: UiState) {
        if (state != UiState.EMPTY) {
            if (state == UiState.CONTENT || state == UiState.PERMISSION) emptyRetries = 0
            return
        }
        if (emptyRetryPending || emptyRetries >= MAX_EMPTY_RETRIES) return
        emptyRetryPending = true
        b.root.postDelayed(emptyRetry, EMPTY_RETRY_DELAY_MS)
    }

    private val emptyRetry = Runnable {
        emptyRetryPending = false
        if (_binding == null || !hasAccess || currentState() != UiState.EMPTY) return@Runnable
        emptyRetries++
        viewModel.refresh(force = true)
    }

    private fun render() {
        val b = binding ?: return
        val state = currentState()
        scheduleEmptyRetry(b, state)
        // Classic and Mosaic have the filter bar; Timeline has its own header.
        val inClassic = mode == Mode.CLASSIC || mode == Mode.MOSAIC || mode == Mode.JOURNAL

        // The grid stays visible when a filter matches nothing, so the filter bar stays usable.
        val listVisible = state == UiState.CONTENT || state == UiState.SWITCHING || (state == UiState.FILTER_EMPTY && inClassic)
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
