package com.example.thirdgallery.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.example.thirdgallery.databinding.FragmentPlaceholderBinding

/** Stand-in content for tabs whose screens are not built yet (Gallery, Albums). */
class PlaceholderFragment : Fragment() {

    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentPlaceholderBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = arguments?.getInt(ARG_TITLE, 0) ?: 0
        if (title != 0) binding?.tvTitle?.setText(title)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TITLE = "title"

        fun newInstance(@StringRes title: Int) = PlaceholderFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TITLE, title) }
        }
    }
}
