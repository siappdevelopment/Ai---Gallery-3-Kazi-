package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.ImageView
import com.bumptech.glide.Glide

/**
 * Stops the image request of [view]. Glide.with() throws once the activity of the view is destroyed, and a RecyclerView
 * recycles its rows exactly then (its adapter is detached in onDestroyView / onDestroy, which also happens when the
 * language changes and the screens are recreated). A destroyed activity has already cancelled its requests through
 * Glide's own lifecycle, so nothing is left to clear and nothing may start.
 */
fun clearImage(view: ImageView) {
    var context: Context? = view.context
    while (context is ContextWrapper) {
        if (context is Activity) {
            if (context.isDestroyed) return
            break
        }
        context = context.baseContext
    }
    Glide.with(view).clear(view)
}
