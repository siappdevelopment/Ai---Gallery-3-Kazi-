package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.model.VaultSummary
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/** The vault home: item counts, folders and the newest items, following the vault live; also creates folders. */
class VaultMediaViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)

    private val _summary = MediatorLiveData<VaultSummary>().apply {
        val items = repo.observeItems()
        val folders = repo.observeFolders()
        fun update() {
            val i = items.value
            val f = folders.value
            if (i != null && f != null) value = repo.summaryOf(i, f)
        }
        addSource(items) { update() }
        addSource(folders) { update() }
    }
    val summary: LiveData<VaultSummary> = _summary

    private val _folderResult = MutableLiveData<Event<VaultRepository.FolderResult>>()
    val folderResult: LiveData<Event<VaultRepository.FolderResult>> = _folderResult

    /** Settles any move that was interrupted while the app was away, then the live list catches up by itself. */
    fun refresh() {
        worker.execute {
            try {
                repo.settleInterrupted()
            } catch (e: Exception) {
                // The list keeps working; the next visit tries again.
            }
        }
    }

    fun createFolder(name: String) {
        worker.execute { _folderResult.postValue(Event(repo.createFolder(name))) }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
