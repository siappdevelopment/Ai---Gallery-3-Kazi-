package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.example.thirdgallery.model.VaultSummary
import com.example.thirdgallery.repo.VaultRepository
import java.util.concurrent.Executors

/** The vault home: item counts, folders and the newest items, following the vault live. */
class VaultMediaViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)

    private val _summary = MediatorLiveData<VaultSummary>().apply {
        addSource(repo.observeItems()) { items -> value = repo.summaryOf(items) }
    }
    val summary: LiveData<VaultSummary> = _summary

    /** Settles any move that was interrupted while the app was away, then the live list catches up by itself. */
    fun refresh() {
        worker.execute {
            try {
                repo.settleInterrupted()
            } catch (e: Exception) {
                // The list below keeps working; the next visit tries again.
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
