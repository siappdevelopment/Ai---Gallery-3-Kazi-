package com.photovideo.gallerykz.memories.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.BottomSheetChoiceBinding
import com.photovideo.gallerykz.memories.databinding.ItemChoiceOptionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * A single choice from a list (Language, Theme, Gallery layout of Settings): label with an optional line under it and
 * the pink check on the current one. The index of the tapped option comes back as a fragment result under [requestKey].
 */
class ChoiceBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = BottomSheetChoiceBinding.inflate(inflater, container, false)
        val args = requireArguments()
        val labels = args.getStringArray(ARG_LABELS) ?: emptyArray()
        val subs = args.getStringArray(ARG_SUBS) ?: emptyArray()
        val selected = args.getInt(ARG_SELECTED, -1)
        val key = args.getString(ARG_KEY).orEmpty()

        val basePadding = b.sheetRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.sheetRoot) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            insets
        }

        b.tvSheetTitle.text = args.getString(ARG_TITLE).orEmpty()
        b.rvOptions.layoutManager = LinearLayoutManager(requireContext())
        b.rvOptions.itemAnimator = null
        b.rvOptions.adapter = OptionAdapter(labels, subs, selected) { index ->
            parentFragmentManager.setFragmentResult(key, bundleOf(RESULT_INDEX to index))
            dismissAllowingStateLoss()
        }
        // A long list (the languages) scrolls inside the sheet instead of growing past the screen.
        val density = resources.displayMetrics.density
        val rowDp = if (subs.any { it.isNotEmpty() }) ROW_WITH_SUB_DP else ROW_DP
        val maxPx = (resources.displayMetrics.heightPixels * MAX_LIST_FRACTION).toInt()
        if (labels.size * rowDp * density > maxPx) {
            b.rvOptions.layoutParams = b.rvOptions.layoutParams.apply { height = maxPx }
        }
        // The list scrolls on its own; it must not hand its scroll to the sheet (which would drag it down).
        b.rvOptions.isNestedScrollingEnabled = false
        b.rvOptions.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(v.canScrollVertically(-1) || v.canScrollVertically(1))
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        if (selected in labels.indices) b.rvOptions.scrollToPosition(selected)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        // The sheet draws its own rounded background, so the container must not add another.
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private class OptionAdapter(
        private val labels: Array<String>,
        private val subs: Array<String>,
        private val selected: Int,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<OptionAdapter.ViewHolder>() {

        override fun getItemCount() = labels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemChoiceOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val b = holder.binding
            val on = position == selected
            b.tvLabel.text = labels[position]
            val sub = subs.getOrNull(position).orEmpty()
            b.tvSub.text = sub
            b.tvSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            b.root.setBackgroundResource(if (on) R.drawable.bg_choice_selected else 0)
            b.ivCheck.setBackgroundResource(if (on) R.drawable.bg_selection_check_on else R.drawable.bg_choice_dot_off)
            if (on) b.ivCheck.setImageResource(R.drawable.ic_check) else b.ivCheck.setImageDrawable(null)
            b.root.setOnClickListener { onPick(holder.bindingAdapterPosition) }
        }

        class ViewHolder(val binding: ItemChoiceOptionBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val RESULT_INDEX = "index"
        private const val ARG_KEY = "key"
        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_SUBS = "subs"
        private const val ARG_SELECTED = "selected"
        private const val TAG = "choice_sheet"
        private const val ROW_DP = 48f
        private const val ROW_WITH_SUB_DP = 64f
        private const val MAX_LIST_FRACTION = 0.55f

        /** [subs] may be empty (no second line) or as long as [labels]; [selected] is the current index or -1. */
        fun show(fm: FragmentManager, requestKey: String, title: String, labels: List<String>, subs: List<String>, selected: Int) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ChoiceBottomSheet().apply {
                arguments = bundleOf(
                    ARG_KEY to requestKey,
                    ARG_TITLE to title,
                    ARG_LABELS to labels.toTypedArray(),
                    ARG_SUBS to subs.toTypedArray(),
                    ARG_SELECTED to selected,
                )
            }.show(fm, TAG)
        }
    }
}
