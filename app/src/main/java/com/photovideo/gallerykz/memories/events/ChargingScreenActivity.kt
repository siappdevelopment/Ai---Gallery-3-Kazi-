package com.photovideo.gallerykz.memories.events

import android.content.Intent
import com.photovideo.gallerykz.memories.R

/** The charger plugged in / out: the battery level and a short note. */
class ChargingScreenActivity : EventPromptActivity() {

    override fun kindFrom(intent: Intent?): EventKind =
        if (EventKind.fromKey(intent?.getStringExtra(EXTRA_KIND)) == EventKind.CHARGE_OUT) EventKind.CHARGE_OUT else EventKind.CHARGE_IN

    override fun bind(kind: EventKind) {
        val level = batteryPercent()
        if (kind == EventKind.CHARGE_OUT) {
            showContent(
                0xFFFFF4E8.toInt(), 0xFFEA580C.toInt(), 0xFFFFEDD5.toInt(), 0xFF9A3412.toInt(),
                getString(R.string.event_charge_out_title), getString(R.string.event_charge_out_success, level),
                R.string.event_charge_out_sub, R.string.event_charge_out_body, "$level%",
                R.drawable.ic_event_hero_charge_out, R.drawable.ic_bolt,
            )
            return
        }
        showContent(
            0xFFE7F8EF.toInt(), 0xFF16A34A.toInt(), 0xFFDDF6E8.toInt(), 0xFF166534.toInt(),
            getString(R.string.event_charge_in_title), getString(R.string.event_charge_in_success, level),
            R.string.event_charge_in_sub, R.string.event_charge_in_body, "$level%",
            R.drawable.ic_event_hero_charge_in, R.drawable.ic_check,
        )
    }
}
