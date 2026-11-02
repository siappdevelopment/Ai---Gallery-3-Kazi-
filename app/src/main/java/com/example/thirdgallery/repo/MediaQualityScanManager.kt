package com.example.thirdgallery.repo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaQualityEntity
import com.example.thirdgallery.database.QualityDao
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.helper.PhotoQuality
import com.example.thirdgallery.model.MediaFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Measures every photo of the library once (see [PhotoQuality]) and keeps the result in Room, so the "Made for you"
 * categories (Best photos, Best portraits, Recently edited) are worked out from the database. The whole gallery is
 * covered, newest photos first, in small batches on background threads; a photo is measured again only when it was
 * edited (its modified time changed) or the measuring rules changed. Photos that cannot be read are recorded and
 * retried in later runs of the app, a few times at most. Everything stays on the device.
 */
@OptIn(FlowPreview::class)
object MediaQualityScanManager {

    private const val TAG = "QualityScan"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var started = false
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Identifies this run of the app, so a photo that failed is not retried again in the same run. */
    private val runStamp = System.currentTimeMillis()

    /** Where the measuring stands, so a screen can tell "still checking" from "nothing found". */
    enum class Status { PENDING, SCANNING, DONE, FAILED }

    private val _status = MutableLiveData(Status.PENDING)
    val status: LiveData<Status> = _status

    /** Starts watching the library (once per process); every change of the media table schedules a scan. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        // The media count is a LiveData: observe it for the life of the process (observers must be added on the main thread).
        Handler(Looper.getMainLooper()).post {
            db.mediaDao().observeCount(MediaFilter.ALL.types, 0).observeForever { changes.tryEmit(Unit) }
        }
        scope.launch {
            changes.debounce(DEBOUNCE_MS).collect { scanSafely(app, db.qualityDao()) }
        }
    }

    /** Looks for new or edited photos soon (a screen that shows the categories calls this when it comes to the front). */
    fun refresh(context: Context) {
        start(context)
        changes.tryEmit(Unit)
    }

    /** Scans now, e.g. after a failure. */
    fun scanNow(context: Context) {
        val app = context.applicationContext
        scope.launch { scanSafely(app, AppDatabase.getInstance(app).qualityDao()) }
    }

    private suspend fun scanSafely(app: Context, dao: QualityDao) {
        if (!MediaPermissionHelper.hasAccess(app)) {
            _status.postValue(Status.DONE)
            return
        }
        try {
            val pending = withContext(Dispatchers.IO) {
                dao.prune() // photos that are gone leave the categories at once
                dao.toScan(1, runStamp, MAX_FAILURES, PhotoQuality.VERSION).isNotEmpty()
            }
            if (pending) {
                _status.postValue(Status.SCANNING)
                scan(app, dao)
            }
            _status.postValue(Status.DONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failed round must not stop the next one.
            Log.e(TAG, "scan round failed: ${e.javaClass.simpleName}")
            _status.postValue(Status.FAILED)
        }
    }

    private suspend fun scan(app: Context, dao: QualityDao) = lock.withLock {
        while (true) {
            val batch = withContext(Dispatchers.IO) { dao.toScan(BATCH_SIZE, runStamp, MAX_FAILURES, PhotoQuality.VERSION) }
            if (batch.isEmpty()) break
            val rows = batch.chunked(PARALLELISM).flatMap { group ->
                withContext(Dispatchers.IO) { group.map { media -> async { measure(app, dao, media) } }.awaitAll() }
            }
            // One transaction per batch: the categories change once, not once per photo.
            withContext(Dispatchers.IO) { dao.upsert(rows) }
            currentCoroutineContext().ensureActive()
        }
    }

    /** The row for [media]: its measurements, or a failed attempt (counted, so it is retried in a later run only). */
    private fun measure(app: Context, dao: QualityDao, media: MediaEntity): MediaQualityEntity {
        val result = try {
            PhotoQuality.analyze(app, media)
        } catch (e: RuntimeException) {
            null // a damaged file must never stop the scan
        }
        if (result != null) {
            return MediaQualityEntity(
                mediaUid = media.uid,
                modifiedMs = media.modifiedMs,
                version = PhotoQuality.VERSION,
                sharpness = result.sharpness,
                exposure = result.exposure,
                score = result.score,
                width = result.width,
                height = result.height,
                editedAtMs = result.editedAtMs,
            )
        }
        val failures = (dao.failuresOf(media.uid, media.modifiedMs, PhotoQuality.VERSION) ?: 0) + 1
        return MediaQualityEntity(media.uid, media.modifiedMs, PhotoQuality.VERSION, 0, 0, 0, 0, 0, 0L, failures, runStamp)
    }

    private const val DEBOUNCE_MS = 3_000L
    private const val BATCH_SIZE = 40
    private const val PARALLELISM = 2
    private const val MAX_FAILURES = 3
}
