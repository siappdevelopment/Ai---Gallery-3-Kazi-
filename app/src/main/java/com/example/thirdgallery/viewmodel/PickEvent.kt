package com.example.thirdgallery.viewmodel

import android.app.PendingIntent
import androidx.annotation.StringRes

/** Something a suggestion screen has to launch (a system confirmation), show, or do (close itself). */
sealed class PickEvent {
    class Launch(val intent: PendingIntent) : PickEvent()
    class Message(@StringRes val res: Int, val count: Int = 0) : PickEvent()
    /** The screen is finished; [message] (if any) is shown first. */
    class Done(@StringRes val message: Int = 0, val count: Int = 0) : PickEvent()
}
