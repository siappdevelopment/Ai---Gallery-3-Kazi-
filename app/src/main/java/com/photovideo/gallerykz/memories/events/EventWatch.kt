package com.photovideo.gallerykz.memories.events

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Listens for the events for as long as the app process lives: the charger plugged in or out, an app installed or removed
 * (not an update, not this app). Registered once from the Application; implicit broadcasts cannot be received through the
 * manifest, and while the process is frozen or killed nothing is received until the app starts again.
 */
object EventWatch {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            if (EventLauncher.isHeldBackDelivery()) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> EventLauncher.open(context, EventKind.CHARGE_IN)
                Intent.ACTION_POWER_DISCONNECTED -> EventLauncher.open(context, EventKind.CHARGE_OUT)
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED -> {
                    val installed = intent.action == Intent.ACTION_PACKAGE_ADDED
                    val packageName = intent.data?.schemeSpecificPart
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || packageName == null || packageName == context.packageName) return
                    EventLauncher.open(context, if (installed) EventKind.INSTALL else EventKind.UNINSTALL)
                }
            }
        }
    }

    fun register(app: Application) {
        if (registered) return
        registered = true
        EventProcessState.install(app)
        val power = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // Not exported: these are system broadcasts, which an app-private receiver still gets.
        ContextCompat.registerReceiver(app, receiver, power, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, receiver, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
