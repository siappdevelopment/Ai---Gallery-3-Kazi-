package com.example.thirdgallery.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.thirdgallery.utils.Event

/** Shared by the media permission screen and the denial screen (one instance per activity). */
class MediaPermissionViewModel : ViewModel() {

    enum class Destination { NOTIFICATION, DENIED }

    private var navigated = false

    private val _navigate = MutableLiveData<Event<Destination>>()
    val navigate: LiveData<Event<Destination>> = _navigate

    /**
     * Result of a permission request. Granted (full or partial) always continues to the
     * notification screen; denied goes to the denial screen only when [canOpenDenied] is true,
     * otherwise the user stays where they are.
     */
    fun onAccessResult(granted: Boolean, canOpenDenied: Boolean) {
        if (navigated) return
        when {
            granted -> emit(Destination.NOTIFICATION)
            canOpenDenied -> emit(Destination.DENIED)
        }
    }

    /** Re-check without a request (screen opened or resumed, e.g. back from Settings). */
    fun onAccessChecked(granted: Boolean) {
        if (granted) onAccessResult(granted = true, canOpenDenied = false)
    }

    private fun emit(destination: Destination) {
        navigated = true
        _navigate.value = Event(destination)
    }
}
