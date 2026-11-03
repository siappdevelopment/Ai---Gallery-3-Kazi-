package com.photovideo.gallerykz.memories.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photovideo.gallerykz.memories.ads.AdRemoteConfig
import com.photovideo.gallerykz.memories.ads.AdScreen
import com.photovideo.gallerykz.memories.ads.AdSlot
import com.photovideo.gallerykz.memories.ads.SlotSpec
import com.photovideo.gallerykz.memories.adapter.AdRowAdapter
import com.photovideo.gallerykz.memories.activity.AlbumDetailActivity
import com.photovideo.gallerykz.memories.activity.TrashActivity
import com.photovideo.gallerykz.memories.activity.VaultMediaActivity
import com.photovideo.gallerykz.memories.activity.VaultSetupActivity
import com.photovideo.gallerykz.memories.adapter.AlbumCardAdapter
import com.photovideo.gallerykz.memories.adapter.AlbumsHeaderAdapter
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.SmartCollectionsAdapter
import com.photovideo.gallerykz.memories.databinding.FragmentAlbumsBinding
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.helper.VaultSession
import com.photovideo.gallerykz.memories.model.AlbumCard
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.repo.VaultRepository
import com.photovideo.gallerykz.memories.viewmodel.AlbumsUi
import com.photovideo.gallerykz.memories.viewmodel.AlbumsViewModel

/**
 * Albums tab (design 18): device folders and the user's albums as cards, plus smart collections.
 * Everything comes from the Room media index, so switching tabs never rescans the library.
 */
class AlbumFragment : Fragment() {

    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding

    private val viewModel: AlbumsViewModel by activityViewModels()

    private var header: AlbumsHeaderAdapter? = null
    private var cards: AlbumCardAdapter? = null
    private var smart: SmartCollectionsAdapter? = null
    private var vaultItems = 0

    // The Albums ad sits inside the grid, in the second row (after the first two album cards).
    private var adSlot: AdSlot? = null
    private var adStarted = false
    private var adLeft = false

    private var latestUi: AlbumsUi? = null
    private var hasAccess = false
    private var requesting = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requesting = false
        val granted = MediaPermissionHelper.hasAccess(requireContext())
        if (!granted && !MediaPermissionHelper.requiredPermissions().any { shouldShowRequestPermissionRationale(it) }) {
            activity?.let { MediaPermissionHelper.openAppSettings(it) }
        }
        syncAccess()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentAlbumsBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        val headerAdapter = AlbumsHeaderAdapter(
            onSort = { showSheet(AlbumSortBottomSheet(), AlbumSortBottomSheet.TAG) },
            onNew = { showSheet(AddToAlbumBottomSheet.newCreateOnly(), AddToAlbumBottomSheet.TAG) },
            onSearch = { viewModel.setQuery(it) },
            onToggleAll = { viewModel.toggleShowAll() },
        )
        val cardAdapter = AlbumCardAdapter { openAlbum(it) }
        val smartAdapter = SmartCollectionsAdapter(
            onOpen = { kind, title -> open(kind, 0L, title) },
            onOpenBin = {
                val ctx = context
                if (ctx != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        startActivity(TrashActivity.intent(ctx))
                    } else {
                        android.widget.Toast.makeText(ctx.applicationContext, R.string.viewer_unsupported, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onOpenVault = { context?.let { openVault(it) } },
        )
        header = headerAdapter
        cards = cardAdapter
        smart = smartAdapter

        val adContainer = android.widget.FrameLayout(requireContext())
        @Suppress("UNCHECKED_CAST")
        val cardInner = cardAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val cardRow = AdRowAdapter(cardInner, adContainer) { if (cardAdapter.itemCount >= AD_AFTER_CARDS) AD_AFTER_CARDS else -1 }
        adSlot = AdSlot(
            requireActivity(), adContainer, viewLifecycleOwner, adSpec(),
            onVisibility = { visible -> cardRow.setEnabled(visible) },
            liveSpec = { adSpec() },
        )

        val manager = GridLayoutManager(requireContext(), 2)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // header (1 row) and the smart collections (last row) are full width
                val cardEnd = 1 + cardRow.itemCount
                return if (position < 1 || position >= cardEnd || cardRow.isAd(position - 1)) 2 else 1
            }
        }
        b.rvAlbums.layoutManager = manager
        b.rvAlbums.adapter = ConcatAdapter(headerAdapter, cardRow, smartAdapter)
        b.rvAlbums.itemAnimator = null
        b.rvAlbums.setHasFixedSize(true)

        b.btnAllow.setOnClickListener { requestAccess() }

        val owner = viewLifecycleOwner
        viewModel.ui.observe(owner) { ui ->
            latestUi = ui
            cardAdapter.submitList(ui.cards)
            renderHeader()
            render()
        }
        viewModel.query.observe(owner) { renderHeader() }
        viewModel.showAll.observe(owner) { renderHeader() }
        viewModel.synced.observe(owner) {
            renderHeader()
            render()
        }
        val updateCounts = { _: Int? ->
            smartAdapter.setCounts(
                viewModel.favoriteCount.value ?: 0,
                viewModel.videoCount.value ?: 0,
                viewModel.screenshotCount.value ?: 0,
                viewModel.recentCount.value ?: 0,
            )
        }
        viewModel.favoriteCount.observe(owner, updateCounts)
        viewModel.videoCount.observe(owner, updateCounts)
        viewModel.screenshotCount.observe(owner, updateCounts)
        viewModel.recentCount.observe(owner, updateCounts)

        VaultRepository(requireContext()).observeCount().observe(owner) {
            vaultItems = it ?: 0
            showVaultCount()
        }

        hasAccess = MediaPermissionHelper.hasAccess(requireContext())
        render()
    }

    override fun onStart() {
        super.onStart()
        if (hasAccess) viewModel.refresh()
        // The vault may have been set up since this tab was last on screen.
        showVaultCount()
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
        if (hidden) adLeft = true else enterAd()
    }

    /**
     * The Albums ad is requested when this tab is really on screen (the first time it is resumed and shown). With `Albums_Ad_Auto_Refresh`
     * on, the timer of the slot refreshes it; with it off, a new ad is requested only when the user comes back after another screen
     * and `Albums_Ad_Auto_Second` has passed since the last request.
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
            if (adSpec().refreshSeconds == 0) slot.refreshNow(AdRemoteConfig.config.screen(AdScreen.ALBUMS).autoSeconds)
        }
    }

    private fun adSpec(): SlotSpec {
        val screen = AdRemoteConfig.config.screen(AdScreen.ALBUMS)
        return SlotSpec(
            show = screen.show && screen.nativeId.isNotEmpty(), native = true, bannerId = "", nativeId = screen.nativeId,
            refreshSeconds = if (screen.autoRefresh) screen.autoSeconds else 0,
            preload = AdRemoteConfig.config.inter.nativePreload, cacheKey = "slot_albums_grid", medium = true,
        )
    }

    /** The vault opens straight away while it is unlocked (auto-lock), otherwise through the PIN screen. */
    private fun openVault(context: android.content.Context) {
        val unlocked = VaultSession.isOpen(context) && VaultRepository(context).isSetUp
        startActivity(if (unlocked) VaultMediaActivity.intent(context) else VaultSetupActivity.intent(context))
    }

    /** "Set up" until the vault has a PIN, then the live number of items in it. */
    private fun showVaultCount() {
        val ctx = context ?: return
        smart?.setVaultCount(if (VaultRepository(ctx).isSetUp) vaultItems else null)
    }

    override fun onDestroyView() {
        binding?.rvAlbums?.adapter = null
        header = null
        cards = null
        smart = null
        adSlot = null
        adStarted = false
        adLeft = false
        latestUi = null
        _binding = null
        super.onDestroyView()
    }

    private fun renderHeader() {
        val ui = latestUi ?: return
        val synced = viewModel.synced.value == true
        header?.update(
            totalAlbums = ui.totalAlbums,
            query = viewModel.query.value.orEmpty(),
            showAll = viewModel.showAll.value == true,
            canToggleAll = ui.canToggleAll,
            noMatch = ui.searching && ui.cards.isEmpty(),
            // Until the first scan finished, "no albums yet" would be wrong.
            anyAlbums = ui.totalAlbums > 0 || !synced,
        )
    }

    private fun render() {
        val b = binding ?: return
        val ui = latestUi
        val synced = viewModel.synced.value == true
        b.stateGroup.visibility = if (hasAccess) View.GONE else View.VISIBLE
        b.rvAlbums.visibility = if (hasAccess) View.VISIBLE else View.INVISIBLE
        b.progress.visibility =
            if (hasAccess && !synced && (ui == null || ui.totalAlbums == 0)) View.VISIBLE else View.GONE
    }

    private fun syncAccess() {
        if (_binding == null) return
        val granted = MediaPermissionHelper.hasAccess(requireContext())
        if (granted != hasAccess) {
            hasAccess = granted
            if (granted) viewModel.refresh()
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

    private fun openAlbum(card: AlbumCard) = open(card.kind, card.id, card.name)

    private fun open(kind: AlbumKind, id: Long, title: String) {
        val context = context ?: return
        startActivity(AlbumDetailActivity.intent(context, kind, id, title))
    }

    private fun showSheet(sheet: androidx.fragment.app.DialogFragment, tag: String) {
        val host = activity as? AppCompatActivity ?: return
        val fm = host.supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(tag) != null) return
        sheet.show(fm, tag)
    }

    private companion object {
        /** The ad row goes after the first row of the 2-column grid (two album cards). */
        const val AD_AFTER_CARDS = 2
    }
}
