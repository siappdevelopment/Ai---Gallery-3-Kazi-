package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.helper.GalleryLayoutPrefs
import com.example.thirdgallery.model.GalleryLayout

/**
 * Selected Gallery layout, shared (activity scope) by the layout picker sheet and the Gallery tab
 * so the choice survives tab switches; it is also persisted for the next launch.
 */
class GalleryLayoutViewModel(application: Application) : AndroidViewModel(application) {

    private val _layout = MutableLiveData(GalleryLayoutPrefs.get(application))
    val layout: LiveData<GalleryLayout> = _layout

    /** Ignores layouts that are not available yet and repeated selections of the current one. */
    fun select(layout: GalleryLayout) {
        if (!layout.implemented || _layout.value == layout) return
        _layout.value = layout
        GalleryLayoutPrefs.set(getApplication(), layout)
    }
}
