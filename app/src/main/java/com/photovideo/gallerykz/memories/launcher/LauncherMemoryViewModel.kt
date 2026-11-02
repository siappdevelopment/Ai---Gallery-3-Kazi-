package com.photovideo.gallerykz.memories.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.photovideo.gallerykz.memories.model.HighlightCard
import com.photovideo.gallerykz.memories.repo.HighlightsRepository
import java.util.concurrent.Executors

/**
 * The Memories card of the home page: the first highlight of For you (its cover, name and number of photos), read from
 * the real media index off the main thread. Null while there is nothing to recap.
 */
class LauncherMemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HighlightsRepository(application)

    private val _card = MutableLiveData<HighlightCard?>()
    val card: LiveData<HighlightCard?> = _card

    /** Rebuilds the card for the current moment (the home page calls it every time it comes back). */
    fun refresh() {
        worker.execute { _card.postValue(repository.load().firstOrNull()) }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
