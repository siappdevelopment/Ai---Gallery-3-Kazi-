package com.example.thirdgallery.helper

import android.app.PendingIntent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.MediaSelection
import com.example.thirdgallery.databinding.ViewSelectionBarsBinding
import com.example.thirdgallery.fragment.AddToAlbumBottomSheet
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.viewmodel.SelectionEvent
import com.example.thirdgallery.viewmodel.SelectionViewModel

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
        bars.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        bars.actShare.setOnClickListener { withSelection { share(it) } }
        bars.actFavorite.setOnClickListener { withSelection { viewModel.favorite() } }
        bars.actAddToAlbum.setOnClickListener { withSelection { showSheet(false) } }
        bars.actMove.setOnClickListener { withSelection { showSheet(true) } }
        bars.actDelete.setOnClickListener { withSelection { confirmDelete(it.size) } }

        activity.onBackPressedDispatcher.addCallback(owner, backCallback)
        activity.supportFragmentManager.setFragmentResultListener(KEY_DELETE, owner) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) viewModel.moveToBin()
        }

        viewModel.active.observe(owner) { render() }
        viewModel.selected.observe(owner) { render() }
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
        ConfirmDialogFragment.show(
            activity.supportFragmentManager,
            KEY_DELETE,
            R.drawable.ic_delete,
            activity.getString(R.string.selection_delete_title, count),
            activity.getString(R.string.delete_body),
            activity.getString(R.string.delete_move_to_bin),
        )
    }

    private companion object {
        const val KEY_DELETE = "delete_selection"
    }
}
