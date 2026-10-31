package com.example.thirdgallery.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.PeopleGridAdapter
import com.example.thirdgallery.adapter.PeopleRow
import com.example.thirdgallery.databinding.ActivityPeopleFaceBinding
import com.example.thirdgallery.fragment.PersonNameSheet
import com.example.thirdgallery.helper.PeopleText
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.PeopleResult
import com.example.thirdgallery.model.PersonUi
import com.example.thirdgallery.repo.FaceScanManager
import com.example.thirdgallery.viewmodel.PeopleAndFaceViewModel

/**
 * People & faces (Screen 10 design): every person found in the photos with their name and photo count, a search
 * over the names, and the "Unnamed groups" that wait for a name. A tap opens the photos of that face group.
 */
class PeopleAndFaceActivity : AppCompatActivity() {

    private var binding: ActivityPeopleFaceBinding? = null
    private val viewModel: PeopleAndFaceViewModel by viewModels()
    private var adapter: PeopleGridAdapter? = null
    private var lastOpenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val b = ActivityPeopleFaceBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val grid = PeopleGridAdapter(onOpen = { open(it) }, onRename = { rename(it) })
        adapter = grid
        val manager = GridLayoutManager(this, COLUMNS)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (grid.isFullWidth(position)) COLUMNS else 1
        }
        b.rvPeople.layoutManager = manager
        b.rvPeople.adapter = grid
        b.rvPeople.itemAnimator = null

        b.btnBack.setOnClickListener { finish() }
        b.etSearch.doAfterTextChanged { viewModel.search(it?.toString().orEmpty()) }
        b.tvStatus.setOnClickListener {
            if (viewModel.scanStatus.value == FaceScanManager.Status.FAILED) viewModel.retry()
        }

        supportFragmentManager.setFragmentResultListener(PersonNameSheet.RESULT_KEY, this) { _, result ->
            viewModel.rename(result.getLong(PersonNameSheet.RESULT_ID), result.getString(PersonNameSheet.RESULT_NAME).orEmpty())
        }

        viewModel.result.observe(this) { render() }
        viewModel.scanStatus.observe(this) { render() }
        viewModel.scanProgress.observe(this) { render() }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render() {
        val b = binding ?: return
        val result = viewModel.result.value ?: PeopleResult(emptyList(), emptyList())
        val hasPeople = result.people.isNotEmpty() || result.unnamed.isNotEmpty()
        val status = PeopleText.status(this, viewModel.scanStatus.value, viewModel.scanProgress.value, hasPeople)

        // The scan line above the list; when there is nothing at all it also is the empty message.
        b.tvStatus.text = status.orEmpty()
        b.tvStatus.visibility = if (status != null && hasPeople) View.VISIBLE else View.GONE

        val rows = ArrayList<PeopleRow>()
        result.people.forEach { rows.add(PeopleRow.Person(it)) }
        when {
            !hasPeople && status != null -> rows.add(PeopleRow.Message(status))
            result.people.isEmpty() && viewModel.searching -> rows.add(PeopleRow.Message(getString(R.string.people_no_match)))
        }
        if (result.unnamed.isNotEmpty()) {
            rows.add(PeopleRow.Section(R.string.people_unnamed_title))
            result.unnamed.forEach { rows.add(PeopleRow.Unnamed(it)) }
        }
        adapter?.submitList(rows)
    }

    private fun open(person: PersonUi) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        val title = person.name ?: getString(R.string.people_unnamed_person)
        startActivity(AlbumDetailActivity.intent(this, AlbumKind.PERSON, person.clusterId, title))
    }

    private fun rename(person: PersonUi) = PersonNameSheet.show(supportFragmentManager, person.clusterId, person.name)

    override fun onDestroy() {
        binding?.rvPeople?.adapter = null
        adapter = null
        binding = null
        super.onDestroy()
    }

    companion object {
        private const val COLUMNS = 3
        private const val OPEN_GAP_MS = 600L

        fun intent(context: Context): Intent = Intent(context, PeopleAndFaceActivity::class.java)
    }
}
