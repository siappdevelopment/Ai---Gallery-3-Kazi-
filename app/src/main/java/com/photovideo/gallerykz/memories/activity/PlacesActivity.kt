package com.photovideo.gallerykz.memories.activity

import com.photovideo.gallerykz.memories.ads.AdAwareActivity

import com.photovideo.gallerykz.memories.ads.AdScreen

import com.photovideo.gallerykz.memories.ads.AdHooks

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.adapter.PlaceGridAdapter
import com.photovideo.gallerykz.memories.database.PlaceSummary
import com.photovideo.gallerykz.memories.databinding.ActivityPlacesBinding
import com.photovideo.gallerykz.memories.fragment.PlaceSortSheet
import com.photovideo.gallerykz.memories.helper.PlaceText
import com.photovideo.gallerykz.memories.model.AlbumKind
import com.photovideo.gallerykz.memories.repo.PlaceScanManager
import com.photovideo.gallerykz.memories.viewmodel.PlacesViewModel

/**
 * Places (Screen 10 design): every place found in the locations saved inside the photos, with a search over the
 * names and a sort button. A tap opens the photos of that place. Nothing here asks for the phone's live location.
 */
class PlacesActivity : AdAwareActivity() {

    private var binding: ActivityPlacesBinding? = null
    private val viewModel: PlacesViewModel by viewModels()
    private var adapter: PlaceGridAdapter? = null
    private var lastOpenAt = 0L

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityPlacesBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        AdHooks.install(this, AdScreen.PLACES)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val grid = PlaceGridAdapter { open(it) }
        adapter = grid
        b.rvPlaces.layoutManager = GridLayoutManager(this, COLUMNS)
        b.rvPlaces.adapter = grid
        b.rvPlaces.itemAnimator = null

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.btnSort.setOnClickListener { PlaceSortSheet.show(supportFragmentManager, viewModel.sort.value ?: PlacesViewModel.Sort.MOST_PHOTOS) }
        b.etSearch.doAfterTextChanged { viewModel.search(it?.toString().orEmpty()) }
        b.tvStatus.setOnClickListener { onStatusTap() }
        b.tvEmpty.setOnClickListener { onStatusTap() }

        supportFragmentManager.setFragmentResultListener(PlaceSortSheet.RESULT_KEY, this) { _, result ->
            viewModel.setSort(PlaceSortSheet.sortOf(result.getInt(PlaceSortSheet.RESULT_SORT)))
        }

        viewModel.places.observe(this) { render() }
        viewModel.query.observe(this) { render() }
        viewModel.sort.observe(this) { render() }
        viewModel.canRead.observe(this) { render() }
        viewModel.scanStatus.observe(this) { render() }
        viewModel.scanProgress.observe(this) { render() }
    }

    override fun onStart() {
        super.onStart()
        // The permission may have been changed in the system settings meanwhile.
        viewModel.refreshPermission()
        viewModel.refresh()
    }

    private fun render() {
        val b = binding ?: return
        val all = viewModel.places.value.orEmpty()
        val shown = viewModel.arrange(all) { PlaceText.name(this, it) }
        adapter?.submitList(shown)

        val canRead = viewModel.canRead.value != false
        val status = PlaceText.status(this, viewModel.scanStatus.value, canRead, viewModel.scanProgress.value, all.isNotEmpty())
        b.tvStatus.text = status.orEmpty()
        b.tvStatus.visibility = if (status != null && all.isNotEmpty()) View.VISIBLE else View.GONE

        // Nothing to list: the status line (or "no match") fills the screen instead.
        val empty = when {
            all.isEmpty() -> status
            shown.isEmpty() -> getString(R.string.places_no_match)
            else -> null
        }
        b.tvEmpty.text = empty.orEmpty()
        b.tvEmpty.visibility = if (empty != null) View.VISIBLE else View.GONE
        b.rvPlaces.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The status line is also the action: ask for the permission, or try the reading again. */
    private fun onStatusTap() {
        when {
            viewModel.canRead.value == false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            viewModel.scanStatus.value == PlaceScanManager.Status.FAILED -> viewModel.retry()
        }
    }

    private fun open(place: PlaceSummary) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(AlbumDetailActivity.intent(this, AlbumKind.PLACE, place.cell, PlaceText.name(this, place)))
    }

    override fun onDestroy() {
        binding?.rvPlaces?.adapter = null
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 2
        private const val OPEN_GAP_MS = 600L

        fun intent(context: Context): Intent = Intent(context, PlacesActivity::class.java)
    }
}
