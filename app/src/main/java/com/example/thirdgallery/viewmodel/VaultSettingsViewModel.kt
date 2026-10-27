package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.database.VaultAlertEntity
import com.example.thirdgallery.repo.MediaRepository
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.utils.Event
import java.util.concurrent.Executors

/** Vault settings, the break-in alert list and "Reset vault" (shared by the settings and the Forgot PIN screens). */
class VaultSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)
    private val media = MediaRepository(application)

    val alerts: LiveData<List<VaultAlertEntity>> = repo.observeAlerts()

    /** Items in the vault, for the reset confirmation. */
    val itemCount: LiveData<Int> = repo.observeCount()

    private val _resetResult = MutableLiveData<Event<Boolean>>()

    /** True when the vault was reset (everything is back in Gallery), false when an item could not be restored. */
    val resetResult: LiveData<Event<Boolean>> = _resetResult

    fun clearAlerts() {
        worker.execute { repo.clearAlerts() }
    }

    /** Puts every item back into the gallery and removes the PIN, fingerprint, recovery question and settings. */
    fun resetVault() {
        worker.execute {
            val done = try {
                repo.resetVault()
            } catch (e: Exception) {
                false
            }
            if (done) media.sync(true) { }
            _resetResult.postValue(Event(done))
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
