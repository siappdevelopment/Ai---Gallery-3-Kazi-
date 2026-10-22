package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.thirdgallery.R
import com.example.thirdgallery.adapter.HighlightAdapter
import com.example.thirdgallery.adapter.PeopleAdapter
import com.example.thirdgallery.adapter.PlaceAdapter
import com.example.thirdgallery.adapter.SuggestionAdapter
import com.example.thirdgallery.databinding.FragmentForYouBinding
import com.example.thirdgallery.helper.HorizontalSpacingDecoration
import com.example.thirdgallery.viewmodel.ForYouViewModel

class ForYouFragment : Fragment() {

    private var _binding: FragmentForYouBinding? = null
    private val binding get() = _binding

    private val viewModel: ForYouViewModel by viewModels()

    private val highlightAdapter = HighlightAdapter()
    private val peopleAdapter = PeopleAdapter()
    private val placeAdapter = PlaceAdapter()
    private val suggestionAdapter = SuggestionAdapter()

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
        viewModel.highlights.observe(owner) { highlightAdapter.submitList(it.orEmpty()) }
        viewModel.persons.observe(owner) { peopleAdapter.submitList(it.orEmpty()) }
        viewModel.places.observe(owner) { placeAdapter.submitList(it.orEmpty()) }
        viewModel.suggestions.observe(owner) { suggestionAdapter.submitList(it.orEmpty()) }
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
        const val PICKS = 24
        const val PORTRAITS = 30
        const val EDITED = 6
        const val DOC_ITEMS = 128
        const val DOC_RECEIPTS = 14
    }
}
