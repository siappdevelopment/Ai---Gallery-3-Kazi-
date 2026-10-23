package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.TrashAdapter
import com.example.thirdgallery.databinding.ActivityTrashBinding
import com.example.thirdgallery.fragment.ConfirmDialogFragment
import com.example.thirdgallery.viewmodel.TrashViewModel

/** The Bin (design 55): trashed photos and videos, Restore all and Empty Bin. */
class TrashActivity : AppCompatActivity() {

    private var binding: ActivityTrashBinding? = null
    private val viewModel: TrashViewModel by viewModels()

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

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { finish() }

        val adapter = TrashAdapter((resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1))
        b.rvBin.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvBin.adapter = adapter
        b.rvBin.itemAnimator = null
        b.rvBin.setHasFixedSize(true)

        b.btnRestoreAll.setOnClickListener { viewModel.restoreAll() }
        b.btnEmptyBin.setOnClickListener { confirmEmpty() }
        supportFragmentManager.setFragmentResultListener(KEY_EMPTY, this) { _, result ->
            if (result.getBoolean(ConfirmDialogFragment.RESULT_CONFIRMED)) viewModel.emptyBin()
        }

        viewModel.items.observe(this) { items ->
            adapter.submitList(items)
            val empty = items.isEmpty()
            b.contentGroup.visibility = if (empty) View.GONE else View.VISIBLE
            b.emptyGroup.visibility = if (empty && viewModel.loaded.value == true) View.VISIBLE else View.GONE
            b.tvSummary.text = getString(R.string.bin_summary, items.size)
        }
        viewModel.loaded.observe(this) { loaded ->
            val empty = viewModel.items.value.orEmpty().isEmpty()
            b.emptyGroup.visibility = if (loaded == true && empty) View.VISIBLE else View.GONE
        }
        viewModel.request.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                requestLauncher.launch(IntentSenderRequest.Builder(it.intent.intentSender).build())
            }
        }
        viewModel.done.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
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

    private fun confirmEmpty() {
        val count = viewModel.items.value.orEmpty().size
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
