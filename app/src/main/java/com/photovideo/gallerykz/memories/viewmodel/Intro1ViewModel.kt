package com.photovideo.gallerykz.memories.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.photovideo.gallerykz.memories.utils.Event

class Intro1ViewModel : ViewModel() {

    private var navigated = false

    private val _navigateNext = MutableLiveData<Event<Unit>>()
    val navigateNext: LiveData<Event<Unit>> = _navigateNext

    fun onNextClicked() {
        if (navigated) return
        navigated = true
        _navigateNext.value = Event(Unit)
    }
}
