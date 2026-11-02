package com.photovideo.gallerykz.memories.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.photovideo.gallerykz.memories.utils.Event

class NotiPermissionViewModel : ViewModel() {

    private var finished = false

    private val _navigateNext = MutableLiveData<Event<Unit>>()
    val navigateNext: LiveData<Event<Unit>> = _navigateNext

    /** The user answered (allowed, denied or "Not now"): the onboarding is complete. */
    fun onFinished() {
        if (finished) return
        finished = true
        _navigateNext.value = Event(Unit)
    }
}
