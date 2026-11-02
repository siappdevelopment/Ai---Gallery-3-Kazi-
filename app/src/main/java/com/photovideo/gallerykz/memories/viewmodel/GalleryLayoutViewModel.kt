package com.photovideo.gallerykz.memories.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.photovideo.gallerykz.memories.helper.GalleryLayoutPrefs
import com.photovideo.gallerykz.memories.model.GalleryLayout
import com.photovideo.gallerykz.memories.model.TimelineZoom

/**
 * Gallery display choices (layout, Timeline zoom, grid columns), shared at activity scope by the
 * layout picker sheet and the Gallery tab so they survive tab switches; all are also persisted.
 */
class GalleryLayoutViewModel(application: Application) : AndroidViewModel(application) {

    private val _layout = MutableLiveData(GalleryLayoutPrefs.getLayout(application))
    val layout: LiveData<GalleryLayout> = _layout

    private val _zoom = MutableLiveData(GalleryLayoutPrefs.getZoom(application))
    val zoom: LiveData<TimelineZoom> = _zoom

    private val _columns = MutableLiveData(GalleryLayoutPrefs.getColumns(application))
    val columns: LiveData<Int> = _columns

    /** Reads the choices again: Settings can change the number of columns while the Gallery tab is not looking. */
    fun reload() {
        GalleryLayoutPrefs.getColumns(getApplication()).let { if (_columns.value != it) _columns.value = it }
        GalleryLayoutPrefs.getLayout(getApplication()).let { if (_layout.value != it) _layout.value = it }
    }

    /** Ignores layouts that are not available and repeated selections of the current one. */
    fun select(layout: GalleryLayout) {
        if (!layout.implemented || _layout.value == layout) return
        _layout.value = layout
        GalleryLayoutPrefs.setLayout(getApplication(), layout)
        // Choosing Timeline always starts at the day view, like the design.
        if (layout == GalleryLayout.TIMELINE) setZoom(TimelineZoom.DAYS)
    }

    fun setZoom(zoom: TimelineZoom) {
        if (_zoom.value == zoom) return
        _zoom.value = zoom
        GalleryLayoutPrefs.setZoom(getApplication(), zoom)
    }

    /** Fewer columns = bigger tiles. Clamped to 2..5 (same bounds as the design's zoom buttons). */
    fun zoomIn() = setColumns((_columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS) - 1)

    fun zoomOut() = setColumns((_columns.value ?: GalleryLayoutPrefs.DEFAULT_COLUMNS) + 1)

    private fun setColumns(value: Int) {
        val clamped = value.coerceIn(GalleryLayoutPrefs.MIN_COLUMNS, GalleryLayoutPrefs.MAX_COLUMNS)
        if (_columns.value == clamped) return
        _columns.value = clamped
        GalleryLayoutPrefs.setColumns(getApplication(), clamped)
    }
}
