package com.example.thirdgallery.fragment

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
import com.example.thirdgallery.activity.AlbumDetailActivity
import com.example.thirdgallery.activity.TrashActivity
import com.example.thirdgallery.adapter.AlbumCardAdapter
import com.example.thirdgallery.adapter.AlbumsHeaderAdapter
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.SmartCollectionsAdapter
import com.example.thirdgallery.databinding.FragmentAlbumsBinding
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.model.AlbumCard
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.viewmodel.AlbumsUi
import com.example.thirdgallery.viewmodel.AlbumsViewModel

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
            onUnavailable = {
                context?.applicationContext?.let {
                    android.widget.Toast.makeText(it, R.string.albums_coming_soon, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
        header = headerAdapter
        cards = cardAdapter
        smart = smartAdapter

        val manager = GridLayoutManager(requireContext(), 2)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // header (1 row) and the smart collections (last row) are full width
                val cardEnd = 1 + cardAdapter.itemCount
                return if (position < 1 || position >= cardEnd) 2 else 1
            }
        }
        b.rvAlbums.layoutManager = manager
        b.rvAlbums.adapter = ConcatAdapter(headerAdapter, cardAdapter, smartAdapter)
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
        binding?.rvAlbums?.adapter = null
        header = null
        cards = null
        smart = null
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
}
