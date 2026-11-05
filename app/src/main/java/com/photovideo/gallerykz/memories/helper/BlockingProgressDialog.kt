package com.photovideo.gallerykz.memories.helper

import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.photovideo.gallerykz.memories.databinding.DialogHideProgressBinding

/**
 * A progress dialog the user cannot dismiss (no Back, no tap outside), shown while a longer job such as Unhide runs, so nothing
 * else can be done on the screen meanwhile. The text counts the items handled ("Unhiding 3 of 20…") above a progress bar.
 * [update] shows it and moves it on; [dismiss] closes it. It also closes with the screen, so it can never leak.
 */
class BlockingProgressDialog(private val activity: AppCompatActivity) {

    private var dialog: AlertDialog? = null
    private var views: DialogHideProgressBinding? = null

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = dismiss()
        })
    }

    /** Shows the dialog if it is not up yet and sets "[done] of [total]" with the text [format] (it takes both numbers). */
    fun update(done: Int, total: Int, @StringRes format: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog == null) {
            val v = DialogHideProgressBinding.inflate(LayoutInflater.from(activity))
            views = v
            dialog = AlertDialog.Builder(activity).setView(v.root).setCancelable(false).create().also {
                it.setCanceledOnTouchOutside(false)
                it.window?.setBackgroundDrawableResource(android.R.color.transparent)
                it.show()
            }
        }
        views?.let {
            it.tvHideProgress.text = activity.getString(format, done, total)
            it.barHideProgress.max = total.coerceAtLeast(1)
            it.barHideProgress.progress = done.coerceIn(0, total.coerceAtLeast(1))
        }
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (e: IllegalArgumentException) {
            // The window is already gone.
        }
        dialog = null
        views = null
    }
}
