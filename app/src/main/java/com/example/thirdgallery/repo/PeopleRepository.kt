package com.example.thirdgallery.repo

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.FacePersonEntity
import com.example.thirdgallery.database.ScanProgress
import com.example.thirdgallery.model.MediaFilter
import com.example.thirdgallery.model.PeopleResult
import com.example.thirdgallery.model.PersonUi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The people found by [FaceScanManager], their names, and the scan progress. Everything stays on the device. */
class PeopleRepository(context: Context) {

    private val app = context.applicationContext
    private val database = AppDatabase.getInstance(app)
    private val dao = database.faceDao()

    /**
     * The people, rebuilt in the background whenever faces, names or the media index change (several changes in a row
     * give one rebuild). Active only while somebody observes it.
     */
    fun observePeople(): LiveData<PeopleResult> = MediatorLiveData<PeopleResult>().also { result ->
        // Per observer, so every screen gets its own rebuilds.
        val dirty = AtomicBoolean(false)
        val rebuild = { _: Any? ->
            // One rebuild runs at a time; a change that arrives meanwhile asks for one more.
            dirty.set(true)
            worker.execute {
                while (dirty.compareAndSet(true, false)) {
                    try {
                        result.postValue(load())
                    } catch (e: Exception) {
                        // Keeps the list that is shown; the next change tries again.
                    }
                }
            }
        }
        result.addSource(dao.observeFaceCount(), rebuild)
        result.addSource(dao.observeGroupedCount(), rebuild)
        result.addSource(dao.observeClusterCount(), rebuild)
        result.addSource(dao.observeNameCount(), rebuild)
        result.addSource(database.mediaDao().observeCount(MediaFilter.ALL.types, 0), rebuild)
    }

    fun observeProgress(): LiveData<ScanProgress> = dao.observeProgress()

    /**
     * Blocking: reads the database. Every group is listed once: a group with a name (given by the user) is a person, every group
     * without one is an unnamed group, however many photos it has. Both lists have the biggest group first.
     */
    fun load(): PeopleResult {
        // A blank name counts as no name, so a group can never sit in the named list without a name to show.
        val names = dao.names().filter { it.name.isNotBlank() }.associate { it.clusterId to it.name.trim() }
        val people = ArrayList<PersonUi>()
        val unnamed = ArrayList<PersonUi>()
        for (count in dao.peopleCounts(UNNAMED_MIN_PHOTOS, GROUP_LIMIT)) {
            val face = dao.personFace(count.clusterId) ?: continue
            val name = names[count.clusterId]
            val person = PersonUi(
                clusterId = count.clusterId,
                name = name,
                photoCount = count.photoCount,
                mediaId = face.mediaId,
                mediaType = face.mediaType,
                boxLeft = face.boxLeft,
                boxTop = face.boxTop,
                boxRight = face.boxRight,
                boxBottom = face.boxBottom,
            )
            if (name != null) people.add(person) else unnamed.add(person)
        }
        return PeopleResult(people, unnamed)
    }

    /** Names a group; an empty name removes it again. */
    fun rename(clusterId: Long, rawName: String) {
        worker.execute {
            val name = rawName.trim()
            if (name.isEmpty()) dao.clearName(clusterId) else dao.setName(FacePersonEntity(clusterId, name))
        }
    }

    companion object {
        /** A face seen in a single photo is listed too (as an unnamed group), so no detected face is hidden. */
        private const val UNNAMED_MIN_PHOTOS = 1
        private const val GROUP_LIMIT = 1500

        private val worker = Executors.newSingleThreadExecutor()
    }
}
