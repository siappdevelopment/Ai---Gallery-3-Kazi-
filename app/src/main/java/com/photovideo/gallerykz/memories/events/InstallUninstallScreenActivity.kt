package com.photovideo.gallerykz.memories.events

import android.content.Intent
import androidx.core.content.ContextCompat
import com.photovideo.gallerykz.memories.R

/** An app installed / uninstalled: a short confirmation in the app's own pink. */
class InstallUninstallScreenActivity : EventPromptActivity() {

    override fun kindFrom(intent: Intent?): EventKind =
        if (EventKind.fromKey(intent?.getStringExtra(EXTRA_KIND)) == EventKind.INSTALL) EventKind.INSTALL else EventKind.UNINSTALL

    override fun bind(kind: EventKind) {
        val header = ContextCompat.getColor(this, R.color.language_item_selected)
        val accent = ContextCompat.getColor(this, R.color.brand_pink)
        if (kind == EventKind.INSTALL) {
            showContent(
                header, accent, 0xFFFFFFFF.toInt(), accent,
                getString(R.string.event_install_title), getString(R.string.event_install_success),
                R.string.event_install_sub, R.string.event_install_body, null,
                R.drawable.ic_event_hero_install, R.drawable.ic_check,
            )
            return
        }
        showContent(
            header, accent, 0xFFFFFFFF.toInt(), accent,
            getString(R.string.event_uninstall_title), getString(R.string.event_uninstall_success),
            R.string.event_uninstall_sub, R.string.event_uninstall_body, null,
            R.drawable.ic_event_hero_uninstall, R.drawable.ic_check,
        )
    }
}
