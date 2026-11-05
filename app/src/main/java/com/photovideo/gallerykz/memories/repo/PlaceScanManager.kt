package com.photovideo.gallerykz.memories.repo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.photovideo.gallerykz.memories.database.AppDatabase
import com.photovideo.gallerykz.memories.database.LocationDao
import com.photovideo.gallerykz.memories.database.MediaEntity
import com.photovideo.gallerykz.memories.database.MediaLocationEntity
import com.photovideo.gallerykz.memories.database.PlaceCells
import com.photovideo.gallerykz.memories.database.PlaceNameEntity
import com.photovideo.gallerykz.memories.helper.MediaPermissionHelper
import com.photovideo.gallerykz.memories.helper.MediaUris
import com.photovideo.gallerykz.memories.helper.MemoryPrefs
import com.photovideo.gallerykz.memories.helper.PlaceNameResolver
import com.photovideo.gallerykz.memories.model.MediaFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads the GPS position saved inside photos (their EXIF metadata, never the phone's live location) and keeps it
 * in Room, so places are worked out from the database and no file is read twice. Each photo is read once, in
 * small batches on background threads, and again only if it was edited. From Android 10 the system hides the
 * position of photos unless the app may read media locations; nothing is read (and nothing cached as "no
 * location") until that permission is granted. After the reading, each new place gets a readable name from the
 * system geocoder (one lookup per place, with a position rounded to about 1 km). Photos without a position get
 * no place: nothing is ever guessed. Adapted from the place_detection skill (detection logic only).
 */
@OptIn(FlowPreview::class)
object PlaceScanManager {

    private const val TAG = "PlaceScan"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var started = false
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Where the reading of photo locations stands, so a screen can tell "still working" from "nothing found". */
    enum class Status { PENDING, SCANNING, DONE, FAILED }

    private val _status = MutableLiveData(Status.PENDING)

    /** PENDING until the first round has begun; SCANNING while it runs; DONE or FAILED when it ended. */
    val status: LiveData<Status> = _status

    /** True when positions of photos can be read: always before Android 10, otherwise with ACCESS_MEDIA_LOCATION. */
    fun canReadLocations(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

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
            changes.debounce(DEBOUNCE_MS).collect { scanSafely(app, db.locationDao()) }
        }
    }

    /** Looks for new or edited photos soon (a screen that shows places calls this when it comes to the front). */
    fun refresh(context: Context) {
        start(context)
        changes.tryEmit(Unit)
    }

    /** Scans now, e.g. right after the permission was granted or after a failure. */
    fun scanNow(context: Context) {
        val app = context.applicationContext
        scope.launch { scanSafely(app, AppDatabase.getInstance(app).locationDao()) }
    }

    private suspend fun scanSafely(app: Context, dao: LocationDao) {
        // Location memories off in Memory controls: no photo is read (what was read stays stored).
        if (!MemoryPrefs.isLocationMemories(app)) {
            _status.postValue(Status.DONE)
            return
        }
        if (!(MediaPermissionHelper.hasAccess(app) && canReadLocations(app))) {
            // Nothing can be read right now; the screens ask for the permission instead.
            _status.postValue(Status.DONE)
            return
        }
        try {
            val pending = withContext(Dispatchers.IO) {
                dao.prune() // photos that are gone leave their places at once
                dao.toScan(1).isNotEmpty()
            }
            if (pending) {
                _status.postValue(Status.SCANNING)
                scan(app, dao)
            }
            nameNewPlaces(app, dao)
            _status.postValue(Status.DONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failed round must not stop the next one.
            Log.e(TAG, "scan round failed: ${e.javaClass.simpleName}")
            _status.postValue(Status.FAILED)
        }
    }

    private suspend fun scan(app: Context, dao: LocationDao) = lock.withLock {
        while (true) {
            val batch = withContext(Dispatchers.IO) { dao.toScan(BATCH_SIZE) }
            if (batch.isEmpty()) break
            val results = batch.chunked(PARALLELISM).flatMap { group ->
                withContext(Dispatchers.IO) { group.map { media -> async { read(app, media) } }.awaitAll() }
            }
            // The permission was taken away while reading: keep what is known, and ask again later.
            if (results.any { it == null }) return@withLock
            withContext(Dispatchers.IO) { dao.upsert(results.filterNotNull()) }
            currentCoroutineContext().ensureActive()
        }
    }

    /** The location of [media] (a not-located result when it has none); null if the file could not be read at all for lack of permission. */
    private fun read(app: Context, media: MediaEntity): MediaLocationEntity? {
        val none = MediaLocationEntity(media.uid, media.modifiedMs, null, 0.0, 0.0)
        return try {
            val uri = MediaUris.of(media.mediaId, media.mediaType)
            // From Android 10 the original file (with its location) must be asked for explicitly.
            val source: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
            val position = app.contentResolver.openInputStream(source)?.use { ExifInterface(it).latLong } ?: return none
            val cell = PlaceCells.of(position[0], position[1]) ?: return none
            MediaLocationEntity(media.uid, media.modifiedMs, cell, position[0], position[1])
        } catch (e: SecurityException) {
            if (canReadLocations(app)) none else null
        } catch (e: UnsupportedOperationException) {
            if (canReadLocations(app)) none else null
        } catch (e: Exception) {
            none // a damaged or unreadable file simply has no location
        }
    }

    /** One lookup per place (never per photo) for the places that have no name yet, 300 ms apart; the answer is kept in Room. */
    private suspend fun nameNewPlaces(app: Context, dao: LocationDao) {
        val pending = withContext(Dispatchers.IO) { dao.places().filter { it.placeName == null } }
        for (place in pending) {
            currentCoroutineContext().ensureActive()
            when (val result = PlaceNameResolver.resolve(app, place.cell, place.latitude, place.longitude)) {
                is PlaceNameResolver.Result.Named -> withContext(Dispatchers.IO) { dao.upsertName(PlaceNameEntity(place.cell, result.name)) }
                PlaceNameResolver.Result.Unknown -> withContext(Dispatchers.IO) { dao.upsertName(PlaceNameEntity(place.cell, "")) }
                PlaceNameResolver.Result.Failed -> Unit // asked again later
            }
            delay(LOOKUP_GAP_MS)
        }
    }

    private const val DEBOUNCE_MS = 3_000L
    private const val BATCH_SIZE = 100
    private const val PARALLELISM = 4
    private const val LOOKUP_GAP_MS = 300L
}
