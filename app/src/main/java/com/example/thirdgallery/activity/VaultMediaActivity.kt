package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.VaultFolderAdapter
import com.example.thirdgallery.adapter.VaultTileAdapter
import com.example.thirdgallery.databinding.ActivityVaultMediaBinding
import com.example.thirdgallery.fragment.VaultFolderSheet
import com.example.thirdgallery.helper.VaultSession
import com.example.thirdgallery.model.VaultFolderKey
import com.example.thirdgallery.model.VaultSummary
import com.example.thirdgallery.viewmodel.VaultMediaViewModel

/**
 * The Photo vault home (designs 69 and 70): item counts, folders and the newest items, with the way to every
 * other vault screen (folders, settings, Add to vault, New folder). Follows the vault live. The vault locks
 * according to its auto-lock setting once this and the other vault screens leave the foreground.
 */
class VaultMediaActivity : VaultScreenActivity() {

    private var binding: ActivityVaultMediaBinding? = null
    private val viewModel: VaultMediaViewModel by viewModels()
    private var allIds: List<Long> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityVaultMediaBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.btnBack.setOnClickListener { finish() }
        b.btnLock.setOnClickListener {
            VaultSession.lock()
            Toast.makeText(applicationContext, R.string.vault_locked, Toast.LENGTH_SHORT).show()
            VaultSession.finishScreens()
        }
        b.btnSettings.setOnClickListener { startActivity(VaultSettingsActivity.intent(this)) }
        b.btnNewFolder.setOnClickListener { VaultFolderSheet.show(supportFragmentManager) }
        b.btnViewAll.setOnClickListener { startActivity(VaultFolderActivity.intent(this, VaultFolderKey.ALL)) }
        b.btnAdd.setOnClickListener { startActivity(VaultPickActivity.intent(this)) }

        val folders = VaultFolderAdapter { startActivity(VaultFolderActivity.intent(this, it.key)) }
        b.rvFolders.layoutManager = GridLayoutManager(this, FOLDER_COLUMNS)
        b.rvFolders.adapter = folders
        b.rvFolders.itemAnimator = null

        val tiles = VaultTileAdapter((resources.displayMetrics.widthPixels / TILE_COLUMNS).coerceAtLeast(1)) { item ->
            startActivity(VaultViewerActivity.intent(this, allIds, item.id))
        }
        b.rvRecent.layoutManager = GridLayoutManager(this, TILE_COLUMNS)
        b.rvRecent.adapter = tiles
        b.rvRecent.itemAnimator = null

        viewModel.summary.observe(this) { summary ->
            allIds = summary.ids
            render(b, summary)
            folders.submitList(summary.folders)
            tiles.submitList(summary.recent)
        }
    }

    private fun render(b: ActivityVaultMediaBinding, summary: VaultSummary) {
        val empty = summary.total == 0
        b.contentGroup.visibility = if (empty) View.GONE else View.VISIBLE
        b.emptyGroup.visibility = if (empty) View.VISIBLE else View.GONE
        b.tvTotal.text = summary.total.toString()
        b.tvPhotos.text = summary.photos.toString()
        b.tvVideos.text = summary.videos.toString()
        b.tvSize.text = Formatter.formatShortFileSize(this, summary.sizeBytes)
    }

    override fun onVaultOpen() = viewModel.refresh()

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val FOLDER_COLUMNS = 2
        private const val TILE_COLUMNS = 3

        fun intent(context: Context): Intent = Intent(context, VaultMediaActivity::class.java)
    }
}
