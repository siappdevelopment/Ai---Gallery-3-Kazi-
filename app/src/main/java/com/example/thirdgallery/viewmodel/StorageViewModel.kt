package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.repo.StorageInfo
import com.example.thirdgallery.repo.StorageRepository
import java.util.concurrent.Executors

/** The Storage screen: what is used on the phone and how much of it is photos and videos. */
class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StorageRepository(application)

    private val _info = MutableLiveData<StorageInfo?>()
    val info: LiveData<StorageInfo?> = _info

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    @Volatile
    private var running = false

    /** Reads the sizes again (never twice at once); the screen asks whenever it comes to the front. */
    fun refresh() {
        if (running) return
        running = true
        worker.execute {
            try {
                _info.postValue(repository.read())
            } finally {
                running = false
                _loaded.postValue(true)
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
