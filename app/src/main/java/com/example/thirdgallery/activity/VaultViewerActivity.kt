package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.VaultPageAdapter
import com.example.thirdgallery.databinding.ActivityVaultViewerBinding
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.viewmodel.VaultViewerViewModel

/** A vault item full screen: swipe through the folder it was opened from, Unhide or Delete the one on screen. */
class VaultViewerActivity : VaultScreenActivity() {

    private var binding: ActivityVaultViewerBinding? = null
    private val viewModel: VaultViewerViewModel by viewModels()
    private var adapter: VaultPageAdapter? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null
    private var ids: List<Long> = emptyList()
    private var position = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val b = ActivityVaultViewerBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        // The bars sit under the system bars, so they take the insets as extra padding.
        val topBase = b.topBar.paddingTop
        val bottomBase = b.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.topBar.setPadding(b.topBar.paddingLeft, topBase + bars.top, b.topBar.paddingRight, b.topBar.paddingBottom)
            b.bottomBar.setPadding(b.bottomBar.paddingLeft, b.bottomBar.paddingTop, b.bottomBar.paddingRight, bottomBase + bars.bottom)
            insets
        }

        val pages = VaultPageAdapter()
        adapter = pages
        val manager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.pager.layoutManager = manager
        b.pager.adapter = pages
        b.pager.itemAnimator = null
        b.pager.setItemViewCacheSize(1)
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(b.pager)
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val page = snap.findSnapView(manager) ?: return
                val index = manager.getPosition(page)
                if (index != position) show(index)
            }
        }
        scrollListener = listener
        b.pager.addOnScrollListener(listener)

        b.btnBack.setOnClickListener { finish() }
        b.actUnhide.setOnClickListener { confirmUnhide() }
        b.actDelete.setOnClickListener { confirmDelete() }

        supportFragmentManager.setFragmentResultListener(KEY_UNHIDE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) ids.getOrNull(position)?.let { viewModel.unhide(it) }
        }
        supportFragmentManager.setFragmentResultListener(KEY_DELETE, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) ids.getOrNull(position)?.let { viewModel.delete(it) }
        }

        viewModel.items.observe(this) { items ->
            if (items.isEmpty()) {
                finish()
                return@observe
            }
            ids = items.map { it.id }
            pages.submitList(items) {
                // Stay on the same item; when it is gone take the one that moved into its place.
                val target = ids.indexOf(viewModel.currentId).takeIf { it >= 0 } ?: position.coerceIn(0, ids.size - 1)
                b.pager.scrollToPosition(target)
                show(target)
            }
        }
        viewModel.message.observe(this) { event ->
            event.getContentIfNotHandled()?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun show(index: Int) {
        val id = ids.getOrNull(index) ?: return
        position = index
        viewModel.currentId = id
        binding?.tvPosition?.text = getString(R.string.viewer_position, index + 1, ids.size)
    }

    private fun confirmUnhide() {
        if (ids.isEmpty()) return
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_UNHIDE,
            R.drawable.ic_vault_restore,
            resources.getQuantityString(R.plurals.vault_unhide_title, 1, 1),
            getString(R.string.vault_unhide_body),
            getString(R.string.vault_unhide),
            danger = false,
        )
    }

    private fun confirmDelete() {
        if (ids.isEmpty()) return
        ConfirmDialogFragment.show(
            supportFragmentManager,
            KEY_DELETE,
            R.drawable.ic_delete_forever,
            resources.getQuantityString(R.plurals.vault_delete_title, 1, 1),
            getString(R.string.vault_delete_body),
            getString(R.string.bin_delete_forever),
        )
    }

    override fun onDestroy() {
        scrollListener?.let { binding?.pager?.removeOnScrollListener(it) }
        scrollListener = null
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_UNHIDE = "vault_viewer_unhide"
        private const val KEY_DELETE = "vault_viewer_delete"

        /** Opens [startId] inside the list of [ids] (item ids, in the order of the folder). */
        fun intent(context: Context, ids: List<Long>, startId: Long): Intent =
            Intent(context, VaultViewerActivity::class.java)
                .putExtra(VaultViewerViewModel.ARG_IDS, ids.toLongArray())
                .putExtra(VaultViewerViewModel.ARG_START, startId)
    }
}
