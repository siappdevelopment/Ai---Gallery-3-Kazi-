package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.VaultPickAdapter
import com.example.thirdgallery.databinding.ActivityVaultPickBinding
import com.example.thirdgallery.helper.VaultSession
import com.example.thirdgallery.viewmodel.VaultPickViewModel

/**
 * Add to vault (designs 76 and 77): the gallery with check marks, "Select all", and a Move button. The move
 * shows a progress card while the files are copied, then the system's delete confirmation, and ends with the
 * items in the vault and gone from Gallery.
 */
class VaultPickActivity : VaultScreenActivity() {

    private var binding: ActivityVaultPickBinding? = null
    private val viewModel: VaultPickViewModel by viewModels()
    private var adapter: VaultPickAdapter? = null

    /** The system's delete confirmation for the originals. */
    private val requestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            VaultSession.hold(false)
            viewModel.onRequestResult(result.resultCode == RESULT_OK)
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        // Nothing to do: the files are being copied, leaving now could lose track of them.
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityVaultPickBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // The delete request that makes this a move is Android 11+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(applicationContext, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pick = VaultPickAdapter(
            thumbSizePx = (resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1),
            isSelected = { viewModel.isSelected(it) },
            onToggle = { viewModel.toggle(it) },
        )
        adapter = pick
        b.rvMedia.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvMedia.adapter = pick
        b.rvMedia.itemAnimator = null

        b.btnBack.setOnClickListener { if (!viewModel.isMoving) finish() }
        b.btnSelectAll.setOnClickListener { viewModel.toggleAll() }
        b.btnMove.setOnClickListener { viewModel.start() }

        viewModel.items.observe(this) { pick.submitData(lifecycle, it) }
        viewModel.total.observe(this) { b.tvEmpty.visibility = if (it == 0) View.VISIBLE else View.GONE }
        viewModel.count.observe(this) { count ->
            val total = viewModel.total.value ?: 0
            b.tvSelected.text = getString(R.string.selection_selected, count)
            b.btnSelectAll.setText(
                if (count > 0 && count >= total) R.string.vault_pick_deselect_all else R.string.selection_select_all,
            )
            b.tvMove.text = if (count > 0) getString(R.string.vault_pick_move, count) else getString(R.string.vault_pick_select_items)
            b.btnMove.alpha = if (count > 0) 1f else 0.5f
            pick.refreshSelection()
        }
        viewModel.progress.observe(this) { progress ->
            backCallback.isEnabled = progress != null
            b.movingOverlay.visibility = if (progress != null) View.VISIBLE else View.GONE
            if (progress != null) {
                val total = progress.total.coerceAtLeast(1)
                b.progress.progress = progress.done * 100 / total
                b.tvProgress.text = getString(R.string.viewer_position, progress.done, progress.total)
            }
        }
        viewModel.request.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                // The system dialog covers the vault; that must not lock it.
                VaultSession.hold(true)
                requestLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            }
        }
        viewModel.result.observe(this) { event ->
            event.getContentIfNotHandled()?.let { result ->
                val message = if (result.failed > 0 && result.moved == 0) R.string.vault_move_failed else R.string.vault_moved
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                if (result.moved > 0) finish()
            }
        }
    }

    override fun onDestroy() {
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3

        fun intent(context: Context): Intent = Intent(context, VaultPickActivity::class.java)
    }
}
