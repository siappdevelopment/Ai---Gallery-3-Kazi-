package com.photovideo.gallerykz.memories.helper

import android.app.PendingIntent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.MediaSelection
import com.photovideo.gallerykz.memories.databinding.ViewSelectionBarsBinding
import com.photovideo.gallerykz.memories.fragment.AddToAlbumBottomSheet
import com.photovideo.gallerykz.memories.fragment.ConfirmDialogFragment
import com.photovideo.gallerykz.memories.viewmodel.SelectionEvent
import com.photovideo.gallerykz.memories.viewmodel.SelectionViewModel

/**
 * The selection bars of the design (top "N selected" bar and the bottom Share / Favorite / Add to
 * album / Move / Delete bar) wired to a [SelectionViewModel]. Used by the Gallery tab and by albums.
 */
class SelectionBarController(
    private val bars: ViewSelectionBarsBinding,
    private val activity: AppCompatActivity,
    private val owner: LifecycleOwner,
    private val viewModel: SelectionViewModel,
    /** Id of the app album the grid shows (items are removed from it on Move), 0 when it is not one. */
    private val sourceAlbumId: () -> Long,
    private val launch: (PendingIntent) -> Unit,
    /** Called after every selection change so the grid can redraw its check marks. */
    private val onSelectionChanged: () -> Unit,
    /** Selection mode started or ended (the Gallery hides its normal bars while it is on). */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.clear()
    }

    /** Adapter-facing view of the selection. */
    val selection = object : MediaSelection {
        override val active: Boolean get() = viewModel.isActive
        override fun isSelected(uid: Long) = viewModel.isSelected(uid)
        override fun onLongPress(uid: Long) = viewModel.enter(uid)
        override fun onToggle(uid: Long) = viewModel.toggle(uid)
    }

    init {
        bars.btnClose.setOnClickListener { viewModel.clear() }
        // Once everything is selected the same button unselects everything again.
        bars.btnSelectAll.setOnClickListener {
            if (viewModel.allSelected.value == true) viewModel.deselectAll() else viewModel.selectAll()
        }
        bars.actShare.setOnClickListener { withSelection { share(it) } }
        bars.actFavorite.setOnClickListener { withSelection { viewModel.favorite() } }
        bars.actAddToAlbum.setOnClickListener { withSelection { showSheet(false) } }
        bars.actMove.setOnClickListener { withSelection { showSheet(true) } }
        bars.actDelete.setOnClickListener { withSelection { confirmDelete(it.size) } }

        activity.onBackPressedDispatcher.addCallback(owner, backCallback)
        activity.supportFragmentManager.setFragmentResultListener(KEY_DELETE, owner) { _, result ->
            if (!result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) return@setFragmentResultListener
            val album = sourceAlbumId()
            if (album > 0L) viewModel.removeFromAlbum(album) else viewModel.moveToBin()
        }

        viewModel.active.observe(owner) { render() }
        viewModel.selected.observe(owner) { render() }
        viewModel.allFavorite.observe(owner) { render() }
        viewModel.events.observe(owner) { event ->
            when (val content = event.getContentIfNotHandled()) {
                is SelectionEvent.Launch -> launch(content.intent)
                is SelectionEvent.Message -> {
                    val text = if (content.count > 0) activity.getString(content.res, content.count) else activity.getString(content.res)
                    Toast.makeText(activity.applicationContext, text, Toast.LENGTH_SHORT).show()
                }
                null -> Unit
            }
        }
    }

    private fun render() {
        val active = viewModel.isActive
        bars.root.visibility = if (active) View.VISIBLE else View.GONE
        backCallback.isEnabled = active
        val unfavorite = viewModel.isUnfavoriteMode
        bars.ivFavoriteAction.setImageResource(if (unfavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        bars.tvFavoriteAction.setText(if (unfavorite) R.string.selection_unfavorite else R.string.viewer_favorite)
        onActiveChanged(active)
        bars.tvSelected.text = activity.getString(R.string.selection_selected, viewModel.count())
        bars.btnSelectAll.setText(
            if (viewModel.allSelected.value == true) R.string.suggest_deselect_all else R.string.selection_select_all,
        )
        onSelectionChanged()
    }

    private inline fun withSelection(block: (List<Long>) -> Unit) {
        val uids = viewModel.snapshot()
        if (uids.isEmpty()) return
        block(uids)
    }

    private fun share(uids: List<Long>) {
        ShareHelper.share(activity, uids) {
            Toast.makeText(activity.applicationContext, R.string.photo_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSheet(move: Boolean) {
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(AddToAlbumBottomSheet.TAG) != null) return
        AddToAlbumBottomSheet.newForSelection(viewModel.snapshot(), move, if (move) sourceAlbumId() else 0L)
            .show(fm, AddToAlbumBottomSheet.TAG)
    }

    private fun confirmDelete(count: Int) {
        if (sourceAlbumId() > 0L) {
            // In an app album Delete only takes the items out of this album; the files and the other albums keep them.
            ConfirmDialogFragment.show(
                activity.supportFragmentManager,
                KEY_DELETE,
                R.drawable.ic_delete,
                activity.getString(R.string.album_remove_selection_title, count),
                activity.getString(R.string.album_remove_body),
                activity.getString(R.string.album_remove_confirm),
            )
            return
        }
        // Anywhere else Delete deletes the real files. Items that are also in one of the user's albums leave those albums as well:
        // the dialog says so before anything happens.
        val uids = viewModel.snapshot()
        com.photovideo.gallerykz.memories.repo.AlbumRepository(activity.applicationContext).countMembers(uids) { inAlbums ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                ConfirmDialogFragment.show(
                    activity.supportFragmentManager,
                    KEY_DELETE,
                    R.drawable.ic_delete,
                    activity.getString(R.string.selection_delete_title, count),
                    if (inAlbums > 0) activity.getString(R.string.delete_body_in_albums, inAlbums) else activity.getString(R.string.delete_body),
                    activity.getString(R.string.delete_move_to_bin),
                )
            }
        }
    }

    private companion object {
        const val KEY_DELETE = "delete_selection"
    }
}
