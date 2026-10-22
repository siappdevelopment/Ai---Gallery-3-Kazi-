package com.example.thirdgallery.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.thirdgallery.utils.Event

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
