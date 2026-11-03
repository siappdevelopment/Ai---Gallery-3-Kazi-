package com.photovideo.gallerykz.memories.ads

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * A see-through screen that only hosts one full-screen ad (the App Open ad after unlocking the phone) and closes itself when
 * the ad is done, failed or has nothing to show. It never takes part in Recents.
 */
class AdHostActivity : AppCompatActivity() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build_O_MR1) {
            setShowWhenLocked(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        val c = AdRemoteConfig.config
        val l = c.launcher
        val ids = AdIds(l.unlockInterId, l.unlockNativeId, l.unlockId.ifEmpty { c.appOpenId })
        val type = SequenceAds.next(this, "unlock_app_open", l.unlockSteps, ids)
        if (type == null) {
            finishAndRemoveTask()
            return
        }
        SequenceAds.run(this, type, ids, l.unlockFailedQuiz) { shown ->
            if (shown) AdGate.markShown(this, "unlock_app_open")
            if (!isFinishing) finishAndRemoveTask()
        }
    }

    private companion object {
        val Build_O_MR1 = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1
    }
}
