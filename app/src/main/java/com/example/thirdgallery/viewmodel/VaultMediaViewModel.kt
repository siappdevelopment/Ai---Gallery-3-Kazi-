package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.model.VaultSummary
import com.example.thirdgallery.repo.VaultRepository
import java.util.concurrent.Executors

/** The vault home: item counts, folders and the newest items. */
class VaultMediaViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)

    private val _summary = MutableLiveData<VaultSummary>()
    val summary: LiveData<VaultSummary> = _summary

    fun refresh() {
        worker.execute { _summary.postValue(repo.loadSummary()) }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
