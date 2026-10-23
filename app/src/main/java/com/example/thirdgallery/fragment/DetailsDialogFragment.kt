package com.example.thirdgallery.fragment

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.example.thirdgallery.databinding.DialogDetailsBinding
import com.example.thirdgallery.databinding.ItemDetailsRowBinding
import com.example.thirdgallery.helper.DetailRow

/** "Details" dialog of the photo viewer (design): info tile, title, label / value rows and Done. */
class DetailsDialogFragment : AppCompatDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val b = DialogDetailsBinding.inflate(layoutInflater)
        val labels = args.getIntArray(ARG_LABELS) ?: IntArray(0)
        val values = args.getStringArray(ARG_VALUES) ?: emptyArray()
        val inflater = LayoutInflater.from(requireContext())
        labels.zip(values).forEach { (label, value) ->
            val row = ItemDetailsRowBinding.inflate(inflater, b.rows, false)
            row.tvLabel.setText(label)
            row.tvValue.text = value
            b.rows.addView(row.root)
        }
        b.btnDone.setOnClickListener { dismissAllowingStateLoss() }
        return Dialog(requireContext()).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(b.root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(DIM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    companion object {
        private const val ARG_LABELS = "labels"
        private const val ARG_VALUES = "values"
        private const val TAG = "details_dialog"
        private const val DIM = 0.42f

        fun show(fm: FragmentManager, rows: List<DetailRow>) {
            if (rows.isEmpty() || fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            DetailsDialogFragment().apply {
                arguments = bundleOf(
                    ARG_LABELS to rows.map { it.label }.toIntArray(),
                    ARG_VALUES to rows.map { it.value }.toTypedArray(),
                )
            }.show(fm, TAG)
        }
    }
}
