package com.photovideo.gallerykz.memories.fragment

import com.photovideo.gallerykz.memories.helper.clearImage
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.photovideo.gallerykz.memories.activity.OverlayPermissionGuideActivity
import com.photovideo.gallerykz.memories.helper.OverlayPermission
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.ads.AdScreen
import com.photovideo.gallerykz.memories.ads.AdSlot
import com.photovideo.gallerykz.memories.ads.SlotSpec
import com.photovideo.gallerykz.memories.activity.AlbumDetailActivity
import com.photovideo.gallerykz.memories.activity.BurstsActivity
import com.photovideo.gallerykz.memories.activity.CategoryMediaActivity
import com.photovideo.gallerykz.memories.activity.SuggestionPickActivity
import com.photovideo.gallerykz.memories.model.SuggestionCard
import com.photovideo.gallerykz.memories.model.SuggestionKind
import com.photovideo.gallerykz.memories.activity.HighlightsDetailActivity
import com.photovideo.gallerykz.memories.activity.MemoryControlActivity
import com.photovideo.gallerykz.memories.activity.PeopleAndFaceActivity
import com.photovideo.gallerykz.memories.activity.PlacesActivity
import com.photovideo.gallerykz.memories.adapter.HighlightAdapter
import com.photovideo.gallerykz.memories.adapter.PeopleAdapter
import com.photovideo.gallerykz.memories.adapter.PlaceAdapter
import com.photovideo.gallerykz.memories.adapter.SuggestionAdapter
import com.photovideo.gallerykz.memories.databinding.FragmentForYouBinding
import com.photovideo.gallerykz.memories.helper.HorizontalSpacingDecoration
import com.photovideo.gallerykz.memories.database.PlaceSummary
import com.photovideo.gallerykz.memories.helper.PeopleText
import com.photovideo.gallerykz.memories.helper.PlaceText
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.model.HighlightCard
import com.photovideo.gallerykz.memories.model.PersonUi
import com.bumptech.glide.Glide
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.repo.FaceScanManager
import com.photovideo.gallerykz.memories.repo.MediaQualityScanManager
import com.photovideo.gallerykz.memories.repo.PlaceScanManager
import com.photovideo.gallerykz.memories.viewmodel.ForYouViewModel

class ForYouFragment : Fragment() {

    private var _binding: FragmentForYouBinding? = null
    private val binding get() = _binding

    private val viewModel: ForYouViewModel by viewModels()

    private val highlightAdapter = HighlightAdapter { openHighlight(it) }
    private var lastOpenAt = 0L
    private var lastOverlayAskAt = 0L
    private val peopleAdapter = PeopleAdapter(onOpen = { openPerson(it) }, onRename = { renamePerson(it) })
    private val placeAdapter = PlaceAdapter { openPlace(it) }

    /** Android 10+: the app may read the GPS saved inside the user's own photos only with this permission. */
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            context?.let { PlaceScanManager.scanNow(it) }
            renderPlaces()
        }
    private val suggestionAdapter = SuggestionAdapter { openSuggestion(it) }
    private var dateReceiver: BroadcastReceiver? = null

    // The For you ad sits between the Highlights and the People & faces blocks.
    private var adSlot: AdSlot? = null
    private var adStarted = false
    private var adLeft = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentForYouBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.btnAllowOverlay.setOnClickListener { askOverlay() }

        adSlot = AdSlot(requireActivity(), b.adForYouSlot, viewLifecycleOwner, adSpec(), liveSpec = { adSpec() })

        b.rvHighlights.adapter = highlightAdapter
        b.rvPeople.adapter = peopleAdapter
        b.rvPlaces.adapter = placeAdapter
        b.rvSuggestions.adapter = suggestionAdapter
        // Gaps from the design: Highlights 10dp, People 14dp, Places 8dp.
        val density = resources.displayMetrics.density
        b.rvHighlights.addItemDecoration(HorizontalSpacingDecoration((10 * density).toInt()))
        b.rvPeople.addItemDecoration(HorizontalSpacingDecoration((14 * density).toInt()))
        b.rvPlaces.addItemDecoration(HorizontalSpacingDecoration((8 * density).toInt()))
        b.btnMemoryC.setOnClickListener { openMemoryControls() }
        // Row items never animate in/out, which keeps the first load flicker-free.
        listOf(b.rvHighlights, b.rvPeople, b.rvPlaces, b.rvSuggestions).forEach { it.itemAnimator = null }

        // Static "Made for you" / documents figures from the design.
        // "Made for you": real counts and cover photos; each card opens the one reusable category screen.
        viewModel.bestCount.observe(viewLifecycleOwner) { renderCategories() }
        viewModel.portraitCount.observe(viewLifecycleOwner) { renderCategories() }
        viewModel.editedCount.observe(viewLifecycleOwner) { renderCategories() }
        viewModel.qualityStatus.observe(viewLifecycleOwner) { renderCategories() }
        viewModel.scanStatus.observe(viewLifecycleOwner) { renderCategories() }
        viewModel.bestCovers.observe(viewLifecycleOwner) { covers ->
            loadCover(b.ivBestBig, covers?.getOrNull(0))
            loadCover(b.ivBestSmall, covers?.getOrNull(1))
        }
        viewModel.portraitCovers.observe(viewLifecycleOwner) { loadCover(b.ivPortrait, it?.getOrNull(0)) }
        viewModel.editedCovers.observe(viewLifecycleOwner) { loadCover(b.ivEdited, it?.getOrNull(0)) }
        b.cardBest.setOnClickListener { openCategory(AlbumKind.BEST_PHOTOS) }
        b.cardPortraits.setOnClickListener { openCategory(AlbumKind.BEST_PORTRAITS) }
        b.cardEdited.setOnClickListener { openCategory(AlbumKind.RECENTLY_EDITED) }

        val owner = viewLifecycleOwner
        viewModel.highlights.observe(owner) { cards ->
            highlightAdapter.submitList(cards.orEmpty())
            // With nothing to recap the whole Highlights block steps aside instead of showing an empty row.
            val visibility = if (cards.isNullOrEmpty()) View.GONE else View.VISIBLE
            b.highlightsHeader.visibility = visibility
            b.rvHighlights.visibility = visibility
        }
        viewModel.people.observe(owner) { renderPeople() }
        viewModel.scanStatus.observe(owner) { renderPeople() }
        viewModel.scanProgress.observe(owner) { renderPeople() }
        b.tvPeopleViewAll.setOnClickListener { context?.let { startActivity(PeopleAndFaceActivity.intent(it)) } }
        b.tvPeopleStatus.setOnClickListener {
            if (viewModel.scanStatus.value == FaceScanManager.Status.FAILED) viewModel.retryScan()
        }
        parentFragmentManager.setFragmentResultListener(PersonNameSheet.RESULT_KEY, owner) { _, result ->
            viewModel.renamePerson(result.getLong(PersonNameSheet.RESULT_ID), result.getString(PersonNameSheet.RESULT_NAME).orEmpty())
        }
        viewModel.places.observe(owner) { renderPlaces() }
        viewModel.placeStatus.observe(owner) { renderPlaces() }
        viewModel.placeProgress.observe(owner) { renderPlaces() }
        // View all only appears when there are more than the six places of the row (see renderPlaces).
        b.tvPlacesViewAll.setOnClickListener { context?.let { startActivity(PlacesActivity.intent(it)) } }
        b.tvPlacesStatus.setOnClickListener { onPlacesStatusTap() }
        viewModel.suggestions.observe(owner) { cards ->
            suggestionAdapter.submitList(cards.orEmpty())
            // With nothing to suggest the whole Suggestions block steps aside instead of showing an empty list.
            val visibility = if (cards.isNullOrEmpty()) View.GONE else View.VISIBLE
            b.suggestionsHeader.visibility = visibility
            b.rvSuggestions.visibility = visibility
        }
    }

    /** The counts under the three "Made for you" cards; "Checking photos…" while a still empty category is being worked out. */
    private fun renderCategories() {
        val b = binding ?: return
        val quality = viewModel.qualityStatus.value
        val checkingQuality = quality == MediaQualityScanManager.Status.SCANNING || quality == MediaQualityScanManager.Status.PENDING
        val faces = viewModel.scanStatus.value
        val checkingFaces = faces == FaceScanManager.Status.SCANNING || faces == FaceScanManager.Status.PENDING
        val best = viewModel.bestCount.value ?: 0
        val portraits = viewModel.portraitCount.value ?: 0
        val edited = viewModel.editedCount.value ?: 0
        b.tvPicks.text = if (best == 0 && checkingQuality) getString(R.string.category_scanning_short) else getString(R.string.home_picks, best)
        b.tvPortraitsCount.text = if (portraits == 0 && (checkingQuality || checkingFaces)) {
            getString(R.string.category_scanning_short)
        } else {
            resources.getQuantityString(R.plurals.intro2_photos, portraits, portraits)
        }
        b.tvEditedCount.text = if (edited == 0 && checkingQuality) {
            getString(R.string.category_scanning_short)
        } else {
            resources.getQuantityString(R.plurals.intro2_photos, edited, edited)
        }
    }

    /** A cover photo of a card; a card without one keeps its plain background. */
    private fun loadCover(view: android.widget.ImageView, uid: Long?) {
        if (uid == null) {
            clearImage(view)
            view.setImageDrawable(null)
            return
        }
        Glide.with(view).load(MediaUris.ofUid(uid)).centerCrop().override(COVER_PX).dontAnimate().into(view)
    }

    /** A suggestion row was tapped: open its own screen (a second tap while it opens is ignored). */
    private fun openSuggestion(card: SuggestionCard) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        val intent = when (card.kind) {
            SuggestionKind.ALBUM -> SuggestionPickActivity.intent(context, SuggestionKind.ALBUM, card.uids, card.fromMs, card.toMs)
            SuggestionKind.FAVORITES -> SuggestionPickActivity.intent(context, SuggestionKind.FAVORITES, card.uids, 0L, 0L)
            SuggestionKind.BURSTS -> BurstsActivity.intent(context)
        }
        startActivity(intent)
    }

    /** A category card was tapped: open the reusable category screen (a second tap while it opens is ignored). */
    private fun openCategory(kind: AlbumKind) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(CategoryMediaActivity.intent(context, kind))
    }

    /** Up to six people in the row (the full list is behind View all); a line tells about the scan when there are none. */
    private fun renderPeople() {
        val b = binding ?: return
        val result = viewModel.people.value
        // The row shows the people first and, while there are fewer than six, the unnamed groups after them.
        val people = (result?.people.orEmpty() + result?.unnamed.orEmpty()).take(MAX_PEOPLE)
        peopleAdapter.submitList(people)
        val status = PeopleText.status(
            requireContext(),
            viewModel.scanStatus.value,
            viewModel.scanProgress.value,
            hasPeople = people.isNotEmpty(),
        )
        b.tvPeopleStatus.text = status.orEmpty()
        b.tvPeopleStatus.visibility = if (status != null && people.isEmpty()) View.VISIBLE else View.GONE
        b.rvPeople.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Up to six places in the row; View all shows only when there are more; a line tells about the reading when there are none. */
    private fun renderPlaces() {
        val b = binding ?: return
        val context = context ?: return
        val all = viewModel.places.value.orEmpty()
        placeAdapter.submitList(all.take(MAX_PLACES))
//        b.tvPlacesViewAll.visibility = if (all.size > MAX_PLACES) View.VISIBLE else View.GONE
        b.rvPlaces.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        val status = PlaceText.status(
            context,
            viewModel.placeStatus.value,
            PlaceScanManager.canReadLocations(context),
            viewModel.placeProgress.value,
            hasPlaces = all.isNotEmpty(),
        )
        b.tvPlacesStatus.text = status.orEmpty()
        b.tvPlacesStatus.visibility = if (status != null && all.isEmpty()) View.VISIBLE else View.GONE
    }

    /** The status line is also the action: ask for the permission, or try the reading again. */
    private fun onPlacesStatusTap() {
        val context = context ?: return
        when {
            !PlaceScanManager.canReadLocations(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            viewModel.placeStatus.value == PlaceScanManager.Status.FAILED -> viewModel.retryPlaces()
        }
    }

    private fun openPlace(place: PlaceSummary) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(AlbumDetailActivity.intent(context, AlbumKind.PLACE, place.cell, PlaceText.name(context, place)))
    }

    private fun openPerson(person: PersonUi) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        val title = person.name ?: getString(R.string.people_unnamed_person)
        startActivity(AlbumDetailActivity.intent(context, AlbumKind.PERSON, person.clusterId, title))
    }

    private fun renamePerson(person: PersonUi) = PersonNameSheet.show(parentFragmentManager, person.clusterId, person.name)

    /**
     * Memory controls opens from here. The start goes through the host activity, which counts it for the click
     * interstitial (Main block) like every other screen opened from For you; a second tap while it opens is ignored.
     */
    private fun openMemoryControls() {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(MemoryControlActivity.intent(context))
    }

    /** A highlight card was tapped: open its detail (a second tap while it opens is ignored). */
    private fun openHighlight(card: HighlightCard) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(HighlightsDetailActivity.intent(context, card))
    }

    override fun onPause() {
        adLeft = true
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) adLeft = true else enterAd()
    }

    /**
     * The For you ad is requested when this tab is really on screen (the first time it is resumed and shown). With
     * `ForYou_Highlights_Ad_Auto_Refresh` on, the timer of the slot refreshes it; with it off, a new ad is requested only when the user
     * comes back after another screen and `ForYou_Highlights_Ad_Auto_Second` has passed since the last request.
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
            if (adSpec().refreshSeconds == 0) slot.refreshNow(AdRemoteConfig.config.screen(AdScreen.FOR_YOU).autoSeconds)
        }
    }

    private fun adSpec(): SlotSpec {
        val screen = AdRemoteConfig.config.screen(AdScreen.FOR_YOU)
        return SlotSpec(
            show = screen.show && screen.nativeId.isNotEmpty(), native = true, bannerId = "", nativeId = screen.nativeId,
            refreshSeconds = if (screen.autoRefresh) screen.autoSeconds else 0,
            preload = AdRemoteConfig.config.inter.nativePreload, cacheKey = "slot_foryou_inline", medium = true,
        )
    }

    override fun onResume() {
        super.onResume()
        enterAd()
        // Back from the system settings (or any other screen): the permission is read again, never remembered.
        showOverlayCard()
    }

    /** The card is shown while "Display over other apps" is not granted; the guide closes itself once it is. */
    private fun showOverlayCard() {
        val b = binding ?: return
        b.cardOverlayPermission.visibility = if (OverlayPermission.isGranted(context)) View.GONE else View.VISIBLE
    }

    private fun askOverlay() {
        val context = context ?: return
        if (OverlayPermission.isGranted(context)) {
            showOverlayCard()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayAskAt < OVERLAY_ASK_GAP_MS) return
        lastOverlayAskAt = now
        try {
            startActivity(OverlayPermission.settingsIntent(context))
            // The tutorial card goes over the system screen; it ends itself when the permission is granted.
            OverlayPermissionGuideActivity.show(context)
        } catch (e: Exception) {
            // No such settings page on this phone: the card simply stays.
        }
    }

    override fun onStart() {
        super.onStart()
        // Cards come and go by date (Monday/Tuesday, the 1st to the 3rd...): rebuild when the screen returns
        // and whenever the day, time or time zone changes while it is open.
        viewModel.refreshHighlights()
        viewModel.refreshPeople()
        viewModel.refreshPlaces()
        viewModel.refreshCategories()
        viewModel.refreshSuggestions()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = viewModel.refreshHighlights()
        }
        dateReceiver = receiver
        requireContext().registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
        )
    }

    override fun onStop() {
        dateReceiver?.let { receiver ->
            try {
                requireContext().unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
        dateReceiver = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.let {
            it.rvHighlights.adapter = null
            it.rvPeople.adapter = null
            it.rvPlaces.adapter = null
            it.rvSuggestions.adapter = null
        }
        adSlot = null
        adStarted = false
        adLeft = false
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val OPEN_GAP_MS = 600L
        const val OVERLAY_ASK_GAP_MS = 1_500L
        const val MAX_PEOPLE = 6
        const val MAX_PLACES = 6
        const val COVER_PX = 600
    }
}
