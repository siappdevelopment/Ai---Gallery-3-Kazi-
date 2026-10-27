package com.example.thirdgallery.fragment

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentManager
import com.example.thirdgallery.R
import com.example.thirdgallery.databinding.DialogConfirmBinding

/**
 * Card dialog of the design (Delete photo, Empty Bin): icon tile, title, message, Cancel and a red
 * confirm button (a pink one for harmless actions such as Unhide). The answer comes back through the fragment result [requestKey].
 */
class ConfirmDialogFragment : AppCompatDialogFragment() {

    private var _binding: DialogConfirmBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val b = DialogConfirmBinding.inflate(layoutInflater)
        _binding = b
        b.ivIcon.setImageResource(args.getInt(ARG_ICON))
        b.tvTitle.text = args.getString(ARG_TITLE).orEmpty()
        b.tvBody.text = args.getString(ARG_BODY).orEmpty()
        b.btnConfirm.text = args.getString(ARG_OK).orEmpty()
        if (!args.getBoolean(ARG_DANGER, true)) {
            b.iconTile.setBackgroundResource(R.drawable.bg_dialog_icon_info)
            ImageViewCompat.setImageTintList(
                b.ivIcon,
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.perm_icon_tint)),
            )
            b.btnConfirm.setBackgroundResource(R.drawable.bg_dialog_primary)
        }
        val key = args.getString(ARG_KEY).orEmpty()
        b.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        b.btnConfirm.setOnClickListener {
            parentFragmentManager.setFragmentResult(key, bundleOf(RESULT_CONFIRMED to true))
            dismissAllowingStateLoss()
        }
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_CONFIRMED = "confirmed"
        private const val ARG_KEY = "key"
        private const val ARG_ICON = "icon"
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        private const val ARG_OK = "ok"
        private const val ARG_DANGER = "danger"
        private const val TAG = "confirm_dialog"
        private const val DIM = 0.42f

        /** Shows the dialog unless one is already open or the state is already saved. */
        fun show(
            fm: FragmentManager,
            requestKey: String,
            @DrawableRes icon: Int,
            title: String,
            body: String,
            confirmLabel: String,
            danger: Boolean = true,
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ConfirmDialogFragment().apply {
                arguments = bundleOf(
                    ARG_KEY to requestKey,
                    ARG_ICON to icon,
                    ARG_TITLE to title,
                    ARG_BODY to body,
                    ARG_OK to confirmLabel,
                    ARG_DANGER to danger,
                )
            }.show(fm, TAG)
        }
    }
}
