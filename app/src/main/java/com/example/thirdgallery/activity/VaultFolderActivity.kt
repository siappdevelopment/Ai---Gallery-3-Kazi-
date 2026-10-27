package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.VaultSelection
import com.example.thirdgallery.adapter.VaultTileAdapter
import com.example.thirdgallery.databinding.ActivityVaultFolderBinding
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.fragment.VaultMoveSheet
import com.example.thirdgallery.model.VaultFolderKey
import com.example.thirdgallery.viewmodel.VaultFolderViewModel

/**
 * One vault folder (designs 72-75): its items in a grid, and a selection mode with Unhide, Move (to another
 * vault folder) and Delete forever. Tapping an item opens it in [VaultViewerActivity].
 */
class VaultFolderActivity : VaultScreenActivity() {

    private var binding: ActivityVaultFolderBinding? = null
    private val viewModel: VaultFolderViewModel by viewModels()
    private var adapter: VaultTileAdapter? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.endSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityVaultFolderBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val selection = object : VaultSelection {
            override val active: Boolean get() = viewModel.isSelecting
            override fun isSelected(id: Long) = viewModel.isSelected(id)
        }
        val tiles = VaultTileAdapter(
            thumbSizePx = (resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1),
            selection = selection,
            onToggle = { viewModel.toggle(it.id) },
            onOpen = { item ->
                val ids = viewModel.items.value.orEmpty().map { it.id }
                startActivity(VaultViewerActivity.intent(this, ids, item.id))
            },
        )
        adapter = tiles
        b.rvItems.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvItems.adapter = tiles
        b.rvItems.itemAnimator = null

        b.btnBack.setOnClickListener { finish() }
        b.btnSelect.setOnClickListener { if (viewModel.isSelecting) viewModel.endSelection() else viewModel.beginSelection() }
        b.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        b.actUnhide.setOnClickListener { withSelection { confirmUnhide(it) } }
        b.actMove.setOnClickListener { withSelection { VaultMoveSheet.show(supportFragmentManager) } }
        b.actDelete.setOnClickListener { withSelection { confirmDelete(it) } }

        supportFragmentManager.setFragmentResultListener(KEY_UNHIDE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) viewModel.unhide()
        }
        supportFragmentManager.setFragmentResultListener(KEY_DELETE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) viewModel.deleteForever()
        }
        supportFragmentManager.setFragmentResultListener(VaultMoveSheet.RESULT_KEY, this) { _, result ->
            val id = result.getLong(VaultMoveSheet.RESULT_FOLDER)
            viewModel.folders.value?.firstOrNull { it.id == id }?.let { viewModel.moveTo(it) }
        }

        viewModel.title.observe(this) { b.tvTitle.text = it }
        viewModel.items.observe(this) { items ->
            tiles.submitList(items)
            b.tvCount.text = resources.getQuantityString(R.plurals.vault_folder_items, items.size, items.size)
            b.emptyGroup.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            // Items that were moved, unhidden or deleted leave the selection too.
            viewModel.prune()
        }
        viewModel.selecting.observe(this) { render() }
        viewModel.selected.observe(this) {
            render()
            tiles.refreshSelection()
        }
        viewModel.done.observe(this) { event ->
            event.getContentIfNotHandled()?.let { done ->
                viewModel.finishAction()
                Toast.makeText(applicationContext, getString(done.messageRes, done.name.orEmpty()), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render() {
        val b = binding ?: return
        val selecting = viewModel.isSelecting
        backCallback.isEnabled = selecting
        b.titleBlock.visibility = if (selecting) View.GONE else View.VISIBLE
        b.topSpace.visibility = if (selecting) View.GONE else View.VISIBLE
        b.tvSelected.visibility = if (selecting) View.VISIBLE else View.GONE
        b.btnSelectAll.visibility = if (selecting) View.VISIBLE else View.GONE
        b.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        b.btnSelect.setText(if (selecting) R.string.photo_cancel else R.string.vault_select)
        b.tvSelected.text = getString(R.string.selection_selected, viewModel.snapshot().size)
        adapter?.refreshSelection()
    }

    private inline fun withSelection(block: (Int) -> Unit) {
        val count = viewModel.snapshot().size
        if (count == 0) {
            Toast.makeText(applicationContext, R.string.vault_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        block(count)
    }

    private fun confirmUnhide(count: Int) {
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_UNHIDE,
            R.drawable.ic_vault_restore,
            resources.getQuantityString(R.plurals.vault_unhide_title, count, count),
            getString(R.string.vault_unhide_body),
            getString(R.string.vault_unhide),
            danger = false,
        )
    }

    private fun confirmDelete(count: Int) {
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_DELETE,
            R.drawable.ic_delete_forever,
            resources.getQuantityString(R.plurals.vault_delete_title, count, count),
            getString(R.string.vault_delete_body),
            getString(R.string.bin_delete_forever),
        )
    }

    override fun onDestroy() {
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3
        private const val KEY_UNHIDE = "vault_unhide"
        private const val KEY_DELETE = "vault_delete"

        fun intent(context: Context, key: String = VaultFolderKey.ALL): Intent =
            Intent(context, VaultFolderActivity::class.java).putExtra(VaultFolderViewModel.ARG_KEY, key)
    }
}
