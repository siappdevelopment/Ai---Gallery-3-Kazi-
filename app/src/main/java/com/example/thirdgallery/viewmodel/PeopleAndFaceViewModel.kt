package com.example.thirdgallery.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.database.ScanProgress
import com.example.thirdgallery.model.PeopleResult
import com.example.thirdgallery.repo.FaceScanManager
import com.example.thirdgallery.repo.PeopleRepository
import java.util.Locale

/** The People & faces screen: every person and unnamed group, the search over names, naming, and the scan state. */
class PeopleAndFaceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PeopleRepository(application)
    private val all: LiveData<PeopleResult> = repository.observePeople()
    private val _query = MutableLiveData("")

    /** The people to show: all of them, or those whose name contains the search text. Unnamed groups are not searched. */
    val result: LiveData<PeopleResult> = MediatorLiveData<PeopleResult>().also { out ->
        val update = {
            val data = all.value
            if (data != null) {
                val q = _query.value.orEmpty().trim().lowercase(Locale.getDefault())
                out.value = if (q.isEmpty()) data else data.copy(
                    people = data.people.filter { it.name?.lowercase(Locale.getDefault())?.contains(q) == true },
                    unnamed = emptyList(),
                )
            }
        }
        out.addSource(all) { update() }
        out.addSource(_query) { update() }
    }

    /** True while a search text is typed (an empty result then means "no match", not "no people"). */
    val searching: Boolean get() = _query.value.orEmpty().isNotBlank()

    val scanStatus: LiveData<FaceScanManager.Status> = FaceScanManager.status
    val scanProgress: LiveData<ScanProgress> = repository.observeProgress()

    init {
        FaceScanManager.start(application)
    }

    fun search(text: String) {
        if (text != _query.value) _query.value = text
    }

    fun rename(clusterId: Long, name: String) = repository.rename(clusterId, name)

    fun refresh() = FaceScanManager.refresh(getApplication())

    fun retry() = FaceScanManager.retry(getApplication())
}
