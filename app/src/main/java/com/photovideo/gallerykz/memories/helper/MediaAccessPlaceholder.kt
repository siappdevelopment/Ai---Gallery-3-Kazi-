package com.photovideo.gallerykz.memories.helper

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.databinding.ViewMediaStateBinding
import com.photovideo.gallerykz.memories.repo.MediaRepository

/**
 * The "Gallery can't see your photos" placeholder of a screen that lists media (the category and album screens), the same state view
 * and the same permission flow as the Gallery tab (`PhotoFragment`): the Allow access button asks for the photo / video access, and
 * after a refusal that the system will not show again it opens the app's Settings page. It is shown while the access is missing and
 * tells its screen through [onAccessChanged] (true = access) so the screen can hide its own list and empty message.
 *
 * Create it in `onCreate` (it registers a permission launcher) and call [refresh] from `onResume`, which also covers coming back from
 * Settings.
 */
class MediaAccessPlaceholder(
    private val activity: ComponentActivity,
    private val state: ViewMediaStateBinding,
    private val onAccessChanged: (Boolean) -> Unit,
) {

    private var hadAccess = MediaPermissionHelper.hasAccess(activity)
    private var requesting = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        val granted = MediaPermissionHelper.hasAccess(activity)
        if (!granted && !MediaPermissionHelper.requiredPermissions().any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
            // "Don't ask again": the only way back is the app's Settings page.
            MediaPermissionHelper.openAppSettings(activity)
        }
        refresh()
    }

    init {
        state.ivStateIcon.setImageResource(R.drawable.ic_no_photography)
        state.tvStateTitle.setText(R.string.perm_denied_title)
        state.tvStateDesc.setText(R.string.photo_permission_desc)
        state.btnState.setText(R.string.photo_allow_access)
        state.btnState.setOnClickListener { requestAccess() }
        refresh()
    }

    /** Reads the access again: shows or hides the placeholder and, when the access just arrived, indexes the library. */
    fun refresh() {
        val granted = MediaPermissionHelper.hasAccess(activity)
        state.root.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted && !hadAccess) {
            // The library is read into the database by the Gallery tab; granted here, the lists need that first read now.
            MediaRepository(activity.applicationContext).sync(force = true) { }
        }
        hadAccess = granted
        onAccessChanged(granted)
    }

    private fun requestAccess() {
        if (requesting) return
        requesting = true
        try {
            launcher.launch(MediaPermissionHelper.requiredPermissions())
        } catch (e: Exception) {
            requesting = false
        }
    }
}
