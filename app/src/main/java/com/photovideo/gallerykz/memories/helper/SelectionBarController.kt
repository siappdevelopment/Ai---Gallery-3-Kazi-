package com.photovideo.gallerykz.memories.helper

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.activity.VaultSetupActivity
import com.photovideo.gallerykz.memories.adapter.MediaSelection
import com.photovideo.gallerykz.memories.databinding.DialogHideProgressBinding
import com.photovideo.gallerykz.memories.databinding.ViewSelectionBarsBinding
import com.photovideo.gallerykz.memories.fragment.AddToAlbumBottomSheet
import com.photovideo.gallerykz.memories.fragment.ConfirmDialogFragment
import com.photovideo.gallerykz.memories.repo.VaultRepository
import com.photovideo.gallerykz.memories.viewmodel.SelectionEvent
import com.photovideo.gallerykz.memories.viewmodel.SelectionViewModel

/**
 * The selection bars of the design (top "N selected" bar and the bottom Share / Favorite / Add to
 * album / Hide / Delete bar) wired to a [SelectionViewModel]. Used by the Gallery tab and by albums.
 */
class SelectionBarController(
    private val bars: ViewSelectionBarsBinding,
    private val activity: AppCompatActivity,
    private val owner: LifecycleOwner,
    private val viewModel: SelectionViewModel,
    /** Id of the app album the grid shows, 0 when it is not one. */
    private val sourceAlbumId: () -> Long,
    private val launch: (PendingIntent) -> Unit,
    /** Opens the vault setup (PIN) when Hide is used before the vault exists; the host passes its result to the view model. */
    private val launchVaultSetup: (Intent) -> Unit,
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
        bars.actHide.setOnClickListener { withSelection { startHide(it.size) } }
        bars.actDelete.setOnClickListener { withSelection { confirmDelete(it.size) } }

        activity.onBackPressedDispatcher.addCallback(owner, backCallback)
        activity.supportFragmentManager.setFragmentResultListener(KEY_DELETE, owner) { _, result ->
            if (!result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) return@setFragmentResultListener
            val album = sourceAlbumId()
            if (album > 0L) viewModel.removeFromAlbum(album) else viewModel.moveToBin()
        }
        activity.supportFragmentManager.setFragmentResultListener(KEY_HIDE, owner) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) viewModel.hide()
        }

        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                hideDialog?.dismiss()
                hideDialog = null
                hideDialogViews = null
            }
        })

        viewModel.active.observe(owner) { render() }
        viewModel.selected.observe(owner) { render() }
        viewModel.allFavorite.observe(owner) { render() }
        viewModel.hideProgress.observe(owner) { showHideProgress(it) }
        viewModel.deleteProgress.observe(owner) { progress ->
            if (progress == null) deleteDialog.dismiss() else deleteDialog.update(progress.done, progress.total, R.string.selection_delete_progress)
        }
        viewModel.events.observe(owner) { event ->
            when (val content = event.getContentIfNotHandled()) {
                is SelectionEvent.Launch -> try {
                    launch(content.intent)
                } catch (e: Exception) {
                    // The system request could not be started (e.g. too large): stop cleanly instead of crashing.
                    viewModel.onRequestResult(false)
                }
                is SelectionEvent.Message -> {
                    val text = if (content.count > 0) activity.getString(content.res, content.count) else activity.getString(content.res)
                    Toast.makeText(activity.applicationContext, text, Toast.LENGTH_SHORT).show()
                }
                is SelectionEvent.HideResult -> Toast.makeText(activity.applicationContext, hideResultText(content), Toast.LENGTH_LONG).show()
                SelectionEvent.ConfirmHide -> confirmHide(viewModel.count())
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

    /** Hide: the vault needs a PIN first (set up once, then it continues here); then the user confirms and the move runs in chunks. */
    private fun startHide(count: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(activity.applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.hideProgress.value != null) return
        if (VaultRepository(activity).isSetUp) {
            confirmHide(count)
        } else {
            viewModel.awaitingVaultSetup = true
            launchVaultSetup(VaultSetupActivity.intent(activity, returnToCaller = true))
        }
    }

    private fun confirmHide(count: Int) {
        if (count <= 0) return
        ConfirmDialogFragment.show(
            activity.supportFragmentManager,
            KEY_HIDE,
            R.drawable.ic_visibility_off,
            activity.getString(R.string.selection_hide_title, count),
            activity.getString(R.string.vault_move_body),
            activity.getString(R.string.vault_move_confirm),
            danger = false,
        )
    }

    /** The same blocking progress dialog as Hide, with the Delete text. */
    private val deleteDialog by lazy { BlockingProgressDialog(activity) }

    private var hideDialog: AlertDialog? = null
    private var hideDialogViews: DialogHideProgressBinding? = null

    /** A progress dialog while Hide copies the items; it goes away when [progress] is null. */
    private fun showHideProgress(progress: SelectionViewModel.HideProgress?) {
        if (progress == null) {
            hideDialog?.dismiss()
            hideDialog = null
            hideDialogViews = null
            return
        }
        if (hideDialog == null) {
            val views = DialogHideProgressBinding.inflate(LayoutInflater.from(activity))
            hideDialogViews = views
            hideDialog = AlertDialog.Builder(activity).setView(views.root).setCancelable(false).create().also { dialog ->
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                if (!activity.isFinishing) dialog.show()
            }
        }
        hideDialogViews?.let {
            it.tvHideProgress.text = activity.getString(R.string.selection_hide_progress, progress.done, progress.total)
            it.barHideProgress.max = progress.total.coerceAtLeast(1)
            it.barHideProgress.progress = progress.done
        }
    }

    private fun hideResultText(result: SelectionEvent.HideResult): String = when {
        result.hidden == 0 -> activity.getString(R.string.vault_move_failed)
        result.failed == 0 -> activity.getString(R.string.selection_hidden, result.hidden)
        else -> activity.getString(R.string.selection_hidden_partial, result.hidden, result.failed)
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
        const val KEY_HIDE = "hide_selection"
    }
}
