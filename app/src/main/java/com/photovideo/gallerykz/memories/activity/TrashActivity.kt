package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.TrashAdapter
import com.photovideo.gallerykz.memories.databinding.ActivityTrashBinding
import com.photovideo.gallerykz.memories.fragment.ConfirmDialogFragment
import com.photovideo.gallerykz.memories.helper.BlockingProgressDialog
import com.photovideo.gallerykz.memories.viewmodel.TrashViewModel

/** The Bin (design 55): trashed photos and videos, Restore all and Empty Bin. */
class TrashActivity : AdAwareActivity() {

    private var binding: ActivityTrashBinding? = null
    private val viewModel: TrashViewModel by viewModels()
    private val progressDialog by lazy { BlockingProgressDialog(this) }

    private val requestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onResult(result.resultCode == RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityTrashBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.TRASH)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val adapter = TrashAdapter((resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1))
        b.rvBin.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvBin.adapter = adapter
        b.rvBin.itemAnimator = null
        b.rvBin.setHasFixedSize(true)

        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = adapter.setSelectionMode(false)
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val updateSelectionUi = {
            val selecting = adapter.selectionMode
            val count = adapter.selectedUids.size
            b.btnSelect.text = getString(if (selecting) R.string.bin_cancel else R.string.bin_select)
            b.btnSelectAll.visibility = if (selecting) View.VISIBLE else View.GONE
            b.btnRestoreAll.text = getString(
                if (selecting) R.string.bin_restore_selected else R.string.bin_restore_all, count,
            )
            b.btnEmptyBin.text = getString(
                if (selecting) R.string.bin_delete_selected else R.string.bin_empty_action, count,
            )
            val enabled = !selecting || count > 0
            b.btnRestoreAll.alpha = if (enabled) 1f else 0.4f
            b.btnEmptyBin.alpha = if (enabled) 1f else 0.4f
            backCallback.isEnabled = selecting
        }
        adapter.onSelectionChanged = { updateSelectionUi() }
        adapter.onItemClick = { item ->
            startActivity(BinPreviewActivity.intent(this, item.uid, item.mediaType))
        }
        b.btnSelect.setOnClickListener { adapter.setSelectionMode(!adapter.selectionMode) }
        b.btnSelectAll.setOnClickListener { adapter.selectAll() }

        b.btnRestoreAll.setOnClickListener {
            if (!adapter.selectionMode) {
                viewModel.restoreAll()
            } else if (adapter.selectedUids.isNotEmpty()) {
                viewModel.restoreAll(adapter.selectedUids.toSet())
            }
        }
        b.btnEmptyBin.setOnClickListener {
            if (!adapter.selectionMode) confirmEmpty(viewModel.items.value.orEmpty().size)
            else if (adapter.selectedUids.isNotEmpty()) confirmEmpty(adapter.selectedUids.size)
        }
        supportFragmentManager.setFragmentResultListener(KEY_EMPTY, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) {
                viewModel.emptyBin(if (adapter.selectionMode) adapter.selectedUids.toSet() else null)
            }
        }

        viewModel.items.observe(this) { items ->
            adapter.submitList(items)
            val empty = items.isEmpty()
            if (empty) adapter.setSelectionMode(false)
            b.btnSelect.visibility = if (empty) View.GONE else View.VISIBLE
            b.contentGroup.visibility = if (empty) View.GONE else View.VISIBLE
            b.emptyGroup.visibility = if (empty && viewModel.loaded.value == true) View.VISIBLE else View.GONE
            b.tvSummary.text = getString(R.string.bin_summary, items.size)
        }
        viewModel.loaded.observe(this) { loaded ->
            val empty = viewModel.items.value.orEmpty().isEmpty()
            b.emptyGroup.visibility = if (loaded == true && empty) View.VISIBLE else View.GONE
        }
        viewModel.progress.observe(this) { progress ->
            if (progress == null) {
                progressDialog.dismiss()
            } else {
                val format = if (progress.action == TrashViewModel.Action.RESTORE) R.string.bin_restore_progress else R.string.selection_delete_progress
                progressDialog.update(progress.done, progress.total, format)
            }
        }
        viewModel.request.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                requestLauncher.launch(IntentSenderRequest.Builder(it.intent.intentSender).build())
            }
        }
        viewModel.done.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                adapter.setSelectionMode(false)
                val message = if (it.action == TrashViewModel.Action.RESTORE) {
                    getString(R.string.bin_restored, it.count)
                } else {
                    getString(R.string.bin_emptied)
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.failed.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                Toast.makeText(applicationContext, R.string.bin_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Items can be trashed or restored elsewhere (the viewer, another app) while this screen was away.
        viewModel.refresh()
    }

    private fun confirmEmpty(count: Int) {
        if (count == 0) return
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_EMPTY,
            R.drawable.ic_delete_forever,
            getString(R.string.bin_empty_dialog_title),
            getString(R.string.bin_empty_dialog_body, count),
            getString(R.string.bin_delete_forever),
        )
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3
        private const val KEY_EMPTY = "empty_bin"

        fun intent(context: Context): Intent = Intent(context, TrashActivity::class.java)
    }
}
