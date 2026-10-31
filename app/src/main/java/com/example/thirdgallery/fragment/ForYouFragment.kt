package com.example.thirdgallery.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.activity.AlbumDetailActivity
import com.example.thirdgallery.activity.HighlightsDetailActivity
import com.example.thirdgallery.activity.PeopleAndFaceActivity
import com.example.thirdgallery.adapter.HighlightAdapter
import com.example.thirdgallery.adapter.PeopleAdapter
import com.example.thirdgallery.adapter.PlaceAdapter
import com.example.thirdgallery.adapter.SuggestionAdapter
import com.example.thirdgallery.databinding.FragmentForYouBinding
import com.example.thirdgallery.helper.HorizontalSpacingDecoration
import com.example.thirdgallery.helper.PeopleText
import com.example.thirdgallery.model.AlbumKind
import com.example.thirdgallery.model.HighlightCard
import com.example.thirdgallery.model.PersonUi
import com.example.thirdgallery.repo.FaceScanManager
import com.example.thirdgallery.viewmodel.ForYouViewModel

class ForYouFragment : Fragment() {

    private var _binding: FragmentForYouBinding? = null
    private val binding get() = _binding

    private val viewModel: ForYouViewModel by viewModels()

    private val highlightAdapter = HighlightAdapter { openHighlight(it) }
    private var lastOpenAt = 0L
    private val peopleAdapter = PeopleAdapter(onOpen = { openPerson(it) }, onRename = { renamePerson(it) })
    private val placeAdapter = PlaceAdapter()
    private val suggestionAdapter = SuggestionAdapter()
    private var dateReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentForYouBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.rvHighlights.adapter = highlightAdapter
        b.rvPeople.adapter = peopleAdapter
        b.rvPlaces.adapter = placeAdapter
        b.rvSuggestions.adapter = suggestionAdapter
        // Gaps from the design: Highlights 10dp, People 14dp, Places 8dp.
        val density = resources.displayMetrics.density
        b.rvHighlights.addItemDecoration(HorizontalSpacingDecoration((10 * density).toInt()))
        b.rvPeople.addItemDecoration(HorizontalSpacingDecoration((14 * density).toInt()))
        b.rvPlaces.addItemDecoration(HorizontalSpacingDecoration((8 * density).toInt()))
        // Row items never animate in/out, which keeps the first load flicker-free.
        listOf(b.rvHighlights, b.rvPeople, b.rvPlaces, b.rvSuggestions).forEach { it.itemAnimator = null }

        // Static "Made for you" / documents figures from the design.
        b.tvPicks.text = getString(R.string.home_picks, PICKS)
        b.tvPortraitsCount.text = resources.getQuantityString(R.plurals.intro2_photos, PORTRAITS, PORTRAITS)
        b.tvEditedCount.text = resources.getQuantityString(R.plurals.intro2_photos, EDITED, EDITED)
        b.tvDocsSummary.text = getString(R.string.home_docs_summary, DOC_ITEMS, DOC_RECEIPTS)

        val owner = viewLifecycleOwner
        viewModel.highlights.observe(owner) { cards ->
            highlightAdapter.submitList(cards.orEmpty())
            // With nothing to recap the whole Highlights block steps aside instead of showing an empty row.
            val visibility = if (cards.isNullOrEmpty()) View.GONE else View.VISIBLE
            b.highlightsHeader.visibility = visibility
            b.rvHighlights.visibility = visibility
        }
        viewModel.people.observe(owner) { renderPeople() }
        viewModel.scanStatus.observe(owner) { renderPeople() }
        viewModel.scanProgress.observe(owner) { renderPeople() }
        b.tvPeopleViewAll.setOnClickListener { context?.let { startActivity(PeopleAndFaceActivity.intent(it)) } }
        b.tvPeopleStatus.setOnClickListener {
            if (viewModel.scanStatus.value == FaceScanManager.Status.FAILED) viewModel.retryScan()
        }
        parentFragmentManager.setFragmentResultListener(PersonNameSheet.RESULT_KEY, owner) { _, result ->
            viewModel.renamePerson(result.getLong(PersonNameSheet.RESULT_ID), result.getString(PersonNameSheet.RESULT_NAME).orEmpty())
        }
        viewModel.places.observe(owner) { placeAdapter.submitList(it.orEmpty()) }
        viewModel.suggestions.observe(owner) { suggestionAdapter.submitList(it.orEmpty()) }
    }

    /** Up to six people in the row (the full list is behind View all); a line tells about the scan when there are none. */
    private fun renderPeople() {
        val b = binding ?: return
        val result = viewModel.people.value
        // The row shows the people first and, while there are fewer than six, the unnamed groups after them.
        val people = (result?.people.orEmpty() + result?.unnamed.orEmpty()).take(MAX_PEOPLE)
        peopleAdapter.submitList(people)
        val status = PeopleText.status(
            requireContext(),
            viewModel.scanStatus.value,
            viewModel.scanProgress.value,
            hasPeople = people.isNotEmpty(),
        )
        b.tvPeopleStatus.text = status.orEmpty()
        b.tvPeopleStatus.visibility = if (status != null && people.isEmpty()) View.VISIBLE else View.GONE
        b.rvPeople.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openPerson(person: PersonUi) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        val title = person.name ?: getString(R.string.people_unnamed_person)
        startActivity(AlbumDetailActivity.intent(context, AlbumKind.PERSON, person.clusterId, title))
    }

    private fun renamePerson(person: PersonUi) = PersonNameSheet.show(parentFragmentManager, person.clusterId, person.name)

    /** A highlight card was tapped: open its detail (a second tap while it opens is ignored). */
    private fun openHighlight(card: HighlightCard) {
        val context = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpenAt < OPEN_GAP_MS) return
        lastOpenAt = now
        startActivity(HighlightsDetailActivity.intent(context, card))
    }

    override fun onStart() {
        super.onStart()
        // Cards come and go by date (Monday/Tuesday, the 1st to the 3rd...): rebuild when the screen returns
        // and whenever the day, time or time zone changes while it is open.
        viewModel.refreshHighlights()
        viewModel.refreshPeople()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = viewModel.refreshHighlights()
        }
        dateReceiver = receiver
        requireContext().registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
        )
    }

    override fun onStop() {
        dateReceiver?.let { receiver ->
            try {
                requireContext().unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
        dateReceiver = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.let {
            it.rvHighlights.adapter = null
            it.rvPeople.adapter = null
            it.rvPlaces.adapter = null
            it.rvSuggestions.adapter = null
        }
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val OPEN_GAP_MS = 600L
        const val MAX_PEOPLE = 6
        const val PICKS = 24
        const val PORTRAITS = 30
        const val EDITED = 6
        const val DOC_ITEMS = 128
        const val DOC_RECEIPTS = 14
    }
}
