package com.photovideo.gallerykz.memories.helper

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One system dialog at a time on the launcher's gallery entry. Two owners can want one there: the launcher popups (the Home role dialog
 * and the display-over-other-apps flow, `LauncherPopups`) and the permission questions of the gallery page (`GalleryPageFragment`:
 * photos and videos first, then notifications, then phone). The media access has priority:
 *  - while a permission dialog is open, or the media question has still to be asked in this app start, no popup starts;
 *  - when a popup is already on screen, the permission questions wait for its end ([addPopupEndListener]) and then go on.
 * The state is read live from its owners (probes), never kept as a copy that could stay set by mistake. Main thread only.
 */
object DialogGate {

    /** Set by the gallery page: tells whether one of its permission dialogs is open right now. */
    @Volatile
    var permissionProbe: (() -> Boolean)? = null

    fun permissionOpen(): Boolean = permissionProbe?.invoke() == true

    /**
     * The photo / video question was put to the user in this app start, or the gallery page finished its questions (set by the gallery
     * page). Once true, the media question never blocks a popup again in this start.
     */
    @Volatile
    var mediaAskedThisStart = false

    /** True while the media question is still to come in this start: the access is missing and it was not asked yet. */
    fun mediaPending(context: Context): Boolean = !mediaAskedThisStart && !MediaPermissionHelper.hasAccess(context)

    /** Set by the launcher popups: tells whether one of them (or the system screen it opened) is up. */
    @Volatile
    var popupProbe: (() -> Boolean)? = null

    fun popupOpen(): Boolean = popupProbe?.invoke() == true

    private val popupEndListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addPopupEndListener(listener: () -> Unit) {
        popupEndListeners.add(listener)
    }

    fun removePopupEndListener(listener: () -> Unit) {
        popupEndListeners.remove(listener)
    }

    /** The launcher popups call this when their flow is over: whoever waited for them may go on. */
    fun popupEnded() {
        popupEndListeners.forEach { it() }
    }
}
