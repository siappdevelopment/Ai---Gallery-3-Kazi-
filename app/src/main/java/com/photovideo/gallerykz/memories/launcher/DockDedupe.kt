package com.photovideo.gallerykz.memories.launcher

/** What the dock knows about the app of one slot: enough to tell whether another slot already shows the same app. */
class DockCandidate(val packageName: String, val label: String, val picture: Int?)

/**
 * Decides which dock slots are shown. The dock asks for the Contacts app, the default dialer, the messages app and the browser
 * separately, and on some phones (Xiaomi / Redmi among them) two of these are the same app, or two apps with the same name and
 * the same picture, so the dock would show one app twice.
 */
object DockDedupe {

    /**
     * For each slot, in dock order, true when its app is shown. A slot without an app (null) is not shown, and an app that is the same
     * as an earlier shown slot (same package, same name ignoring case, or the same picture) is not shown a second time.
     */
    fun shown(slots: List<DockCandidate?>): List<Boolean> {
        val packages = HashSet<String>()
        val labels = HashSet<String>()
        val pictures = HashSet<Int>()
        return slots.map { candidate ->
            if (candidate == null) return@map false
            val label = candidate.label.trim().lowercase()
            val duplicate = candidate.packageName in packages ||
                (label.isNotEmpty() && label in labels) ||
                (candidate.picture != null && candidate.picture in pictures)
            if (!duplicate) {
                packages.add(candidate.packageName)
                if (label.isNotEmpty()) labels.add(label)
                candidate.picture?.let { pictures.add(it) }
            }
            !duplicate
        }
    }
}
