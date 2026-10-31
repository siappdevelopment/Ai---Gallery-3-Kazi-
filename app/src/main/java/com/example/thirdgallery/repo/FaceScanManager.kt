package com.example.thirdgallery.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.FaceDao
import com.example.thirdgallery.database.FaceEntity
import com.example.thirdgallery.database.FaceScanEntity
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.helper.FaceDescriptor
import com.example.thirdgallery.helper.FaceDetectorWarmup
import com.example.thirdgallery.helper.MediaPermissionHelper
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.model.MediaFilter
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds and groups the people in the library, in the background and only on the device (nothing is uploaded).
 *
 * Each photo is looked at once (at about 800 px, never at full size): it is decoded upright, faces and their eyes are
 * found with ML Kit, every clear, fairly frontal face gets a [FaceDescriptor], and the faces are stored in Room one
 * batch of 12 photos at a time, so the list fills in while the scan runs and an interrupted scan carries on where it
 * stopped. A photo is looked at again only if it was edited. Photos are processed by 2-3 parallel workers, newest first.
 *
 * Faces are grouped while the scan runs: a face joins a person when it is clearly closest to that person's average
 * face, starts a new person when it matches nobody, and in between waits; when the scan is done, a face that is
 * still waiting becomes a person of its own, so no detected face is ever dropped by the grouping. People keep their
 * ids because existing faces are never regrouped. See [FaceDescriptor] for what the signature can tell apart.
 *
 * Progress, counts and failures are logged under [TAG] (numbers only, never image content or file names). Adapted
 * from the face-detection-batch skill; the constants are the skill's tuned values.
 */
@OptIn(FlowPreview::class)
object FaceScanManager {

    private const val TAG = "FaceScan"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var started = false

    /** Identifies this run of the app: a photo that failed in an earlier run is tried again, one that failed now is not. */
    private val runStamp = System.currentTimeMillis()
    private var failedRounds = 0
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Where the scan stands, so a screen can tell "still working" from "nobody found" and from a problem. */
    enum class Status { PENDING, SCANNING, DONE, FAILED, NO_ACCESS }

    private val _status = MutableLiveData(Status.PENDING)

    /** PENDING until the first round has begun; SCANNING while it runs; DONE, FAILED or NO_ACCESS when it ended. */
    val status: LiveData<Status> = _status

    /** Scans again now, e.g. after a failure. */
    fun retry(context: Context) {
        val app = context.applicationContext
        scope.launch { round(app, AppDatabase.getInstance(app).faceDao()) }
    }

    /** Looks for new or edited photos soon (a screen that shows people calls this when it comes to the front). */
    fun refresh(context: Context) {
        start(context)
        changes.tryEmit(Unit)
    }

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
            changes.debounce(DEBOUNCE_MS).collect { round(app, db.faceDao()) }
        }
    }

    private suspend fun round(app: Context, dao: FaceDao) {
        if (!MediaPermissionHelper.hasAccess(app)) {
            Log.w(TAG, "no access to the photos; nothing scanned")
            _status.postValue(Status.NO_ACCESS)
            return
        }
        _status.postValue(Status.SCANNING)
        try {
            val ok = scan(app, dao)
            _status.postValue(if (ok) Status.DONE else Status.FAILED)
            if (ok) failedRounds = 0 else retryLater()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failed round must not stop the next one.
            Log.e(TAG, "scan round failed: ${e.javaClass.simpleName}")
            _status.postValue(Status.FAILED)
            retryLater()
        }
    }

    /** A failed round (the detector could not load, most photos unreadable) is tried again after a growing pause, a few times. */
    private fun retryLater() {
        if (failedRounds >= MAX_AUTO_RETRIES) return
        failedRounds++
        scope.launch {
            kotlinx.coroutines.delay(RETRY_DELAY_MS * failedRounds)
            changes.tryEmit(Unit)
        }
    }

    /** Counts of one round, for the log. */
    private class Stats {
        var photos = 0
        var photosWithFaces = 0
        var faces = 0
        var usable = 0
        var skippedSmall = 0
        var skippedTurned = 0
        var skippedNoEyes = 0
        var decodeFailed = 0

        override fun toString() = "photos=$photos withFaces=$photosWithFaces faces=$faces usable=$usable " +
            "skippedSmall=$skippedSmall skippedTurned=$skippedTurned skippedNoEyes=$skippedNoEyes decodeFailed=$decodeFailed"
    }

    /** What looking at one photo gave. */
    private sealed interface Outcome {
        class Looked(val faces: List<FaceEntity>, val found: Int, val small: Int, val turned: Int, val noEyes: Int) : Outcome
        object DecodeFailed : Outcome
        object DetectorUnavailable : Outcome
    }

    /** True when the round finished; false when the face detector was not available or most photos could not be decoded. */
    private suspend fun scan(app: Context, dao: FaceDao): Boolean = lock.withLock {
        // Nothing new or edited: no detector is created and no native model is loaded.
        val pending = withContext(Dispatchers.IO) {
            dao.prune() // faces of deleted or vaulted photos leave their people at once
            dao.toScan(1, runStamp, MAX_ATTEMPTS).isNotEmpty() || dao.anyUngroupedId() != null
        }
        if (!pending) return@withLock true

        // One detector per worker, each with its own executor: ML Kit runs a detector's work on a single thread, so
        // with one shared detector the "parallel" workers would only queue behind each other.
        val executors = List(workerCount()) { Executors.newSingleThreadExecutor() }
        val detectors = executors.map { executor ->
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(MIN_DETECT_SIZE)
                    .setExecutor(executor)
                    .build()
            )
        }
        try {
            // The detectors' native models are loaded one after the other (never at the same moment): their parallel
            // native initialisation crashed the process. A detector that cannot load = "unavailable", retried later.
            val ready = withContext(Dispatchers.IO) { detectors.all { FaceDetectorWarmup.warmUp(it) } }
            if (!ready) {
                Log.e(TAG, "face detector could not be loaded; the round is retried later")
                false
            } else {
                scanPhotos(app, dao, detectors)
            }
        } finally {
            detectors.forEach { runCatching { it.close() } }
            executors.forEach { it.shutdown() }
        }
    }

    /** 2 or 3 workers, by the number of cores. */
    private fun workerCount(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 3)

    private suspend fun scanPhotos(app: Context, dao: FaceDao, detectors: List<FaceDetector>): Boolean {
        val stats = Stats()
        var sinceGrouping = 0
        var loggedAt = 0
        Log.i(TAG, "scan started with ${detectors.size} workers")
        while (true) {
            // Newest photos first (see FaceDao.toScan), in small batches: the first people appear after a few batches.
            val batch = withContext(Dispatchers.IO) { dao.toScan(BATCH_SIZE, runStamp, MAX_ATTEMPTS) }
            if (batch.isEmpty()) break
            val next = AtomicInteger(0)
            val unavailable = AtomicBoolean(false)
            val results = ConcurrentLinkedQueue<Pair<FaceScanEntity, List<FaceEntity>>>()
            // Every worker takes the next photo as soon as it is free and hands its result over; the batch is stored
            // below in one transaction.
            coroutineScope {
                detectors.map { detector ->
                    async(Dispatchers.IO) {
                        while (!unavailable.get()) {
                            ensureActive()
                            val index = next.getAndIncrement()
                            if (index >= batch.size) break
                            val media = batch[index]
                            val outcome = analyse(app, media, detector)
                            if (outcome is Outcome.DetectorUnavailable) {
                                unavailable.set(true)
                                break
                            }
                            // A photo that could not be read counts its failures, so it is retried a few times, not forever.
                            val failures = if (outcome is Outcome.Looked) 0 else (dao.failuresOf(media.uid, media.modifiedMs) ?: 0) + 1
                            results.add(record(media, outcome, stats, failures))
                        }
                    }
                }.awaitAll()
            }
            // One transaction per batch: the list is updated once per batch, not once per photo. A cancelled round loses only this batch.
            if (results.isNotEmpty()) withContext(Dispatchers.IO) { dao.replaceBatch(results.toList()) }
            if (unavailable.get()) {
                Log.e(TAG, "face detector unavailable; stopped after $stats")
                return false // retried on the next round
            }
            sinceGrouping += batch.size
            // Early on, group after every batch so the first people show up quickly; later less often.
            val every = if (stats.photos < EARLY_PHOTOS) EARLY_GROUP_EVERY else GROUP_EVERY
            if (sinceGrouping >= every) {
                group(dao, finalPass = false)
                sinceGrouping = 0
            }
            if (stats.photos / LOG_EVERY != loggedAt) {
                loggedAt = stats.photos / LOG_EVERY
                Log.i(TAG, "progress: $stats")
            }
        }
        group(dao, finalPass = true)
        Log.i(TAG, "scan finished: $stats")
        // Most photos not decodable on this device is a problem of this device, not an empty library.
        if (stats.photos >= MIN_PHOTOS_FOR_VERDICT && stats.decodeFailed * 2 > stats.photos) {
            Log.e(TAG, "most photos could not be decoded")
            return false
        }
        return true
    }

    /** Counts one photo and returns what to store for it. A photo that could not be read is recorded too, so it is not retried. */
    private fun record(media: MediaEntity, outcome: Outcome, stats: Stats, failures: Int): Pair<FaceScanEntity, List<FaceEntity>> {
        val scan = FaceScanEntity(media.uid, media.modifiedMs, failures, runStamp)
        return when (outcome) {
            is Outcome.Looked -> {
                synchronized(stats) {
                    stats.faces += outcome.found
                    stats.usable += outcome.faces.size
                    stats.skippedSmall += outcome.small
                    stats.skippedTurned += outcome.turned
                    stats.skippedNoEyes += outcome.noEyes
                    if (outcome.faces.isNotEmpty()) stats.photosWithFaces++
                    stats.photos++
                }
                scan to outcome.faces
            }
            else -> {
                synchronized(stats) {
                    stats.decodeFailed++
                    stats.photos++
                }
                scan to emptyList()
            }
        }
    }

    /** Looks at one photo. Never throws; reports why when it could not be looked at. */
    private fun analyse(app: Context, media: MediaEntity, detector: FaceDetector): Outcome {
        var bitmap: Bitmap? = null
        return try {
            bitmap = decode(app, MediaUris.of(media.mediaId, media.mediaType)) ?: return Outcome.DecodeFailed
            val image = bitmap
            val found = Tasks.await(detector.process(InputImage.fromBitmap(image, 0)))
            val shorter = min(image.width, image.height).toFloat()
            var small = 0
            var turned = 0
            var noEyes = 0
            val usable = ArrayList<Face>()
            for (face in found) {
                val box = face.boundingBox
                when {
                    min(box.width(), box.height()) / shorter < MIN_FACE_RATIO -> small++
                    abs(face.headEulerAngleY) > MAX_YAW || abs(face.headEulerAngleZ) > MAX_ROLL -> turned++
                    face.getLandmark(FaceLandmark.LEFT_EYE) == null || face.getLandmark(FaceLandmark.RIGHT_EYE) == null -> noEyes++
                    else -> usable += face
                }
            }
            val entities = usable.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                .take(MAX_FACES)
                .mapNotNull { toEntity(media.uid, image, it) }
            Outcome.Looked(entities, found.size, small, turned, noEyes)
        } catch (e: ExecutionException) {
            if (e.cause is MlKitException) {
                Outcome.DetectorUnavailable
            } else {
                Log.w(TAG, "detection failed for a photo: ${e.cause?.javaClass?.simpleName}")
                Outcome.DecodeFailed
            }
        } catch (e: Exception) {
            Log.w(TAG, "photo skipped: ${e.javaClass.simpleName}")
            Outcome.DecodeFailed
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "photo skipped: out of memory")
            Outcome.DecodeFailed
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * The photo as an upright ARGB_8888 picture of about [TARGET_SIDE] px on its longer side, or null. Tries the system
     * decoder first (Android 9+: handles HEIC and applies the EXIF orientation itself), then [BitmapFactory].
     */
    private fun decode(app: Context, uri: Uri): Bitmap? {
        val resolver = app.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // pixels must be readable by ML Kit
                    val longSide = max(info.size.width, info.size.height)
                    var sample = 1
                    while (longSide / (sample * 2) >= TARGET_SIDE) sample *= 2
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
                return if (decoded.config == Bitmap.Config.ARGB_8888) {
                    decoded
                } else {
                    decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
                }
            } catch (e: Exception) {
                // Fall back to the classic decoder below.
            } catch (e: OutOfMemoryError) {
                return null
            }
        }
        return try {
            val orientation = try {
                resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            } catch (e: Exception) {
                null
            } ?: ExifInterface.ORIENTATION_NORMAL
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val upright = rotated(raw, orientation)
            if (upright !== raw) raw.recycle()
            upright
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun toEntity(mediaUid: Long, image: Bitmap, face: Face): FaceEntity? {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val descriptor = FaceDescriptor.of(image, PointF(left.x, left.y), PointF(right.x, right.y)) ?: return null
        val box = face.boundingBox
        val width = image.width
        val height = image.height
        return FaceEntity(
            mediaUid = mediaUid,
            boxLeft = box.left.coerceIn(0, width) / width.toFloat(),
            boxTop = box.top.coerceIn(0, height) / height.toFloat(),
            boxRight = box.right.coerceIn(0, width) / width.toFloat(),
            boxBottom = box.bottom.coerceIn(0, height) / height.toFloat(),
            descriptor = descriptor,
        )
    }

    private fun rotated(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** A person's running total of descriptors; its direction is the person's average face. */
    private class Person(val sum: FloatArray) {
        var norm = length(sum)
        var faces = 1

        fun similarity(v: FloatArray, vNorm: Float): Float {
            var dot = 0f
            for (i in v.indices) dot += v[i] * sum[i]
            return if (norm > 0f && vNorm > 0f) dot / (norm * vNorm) else 0f
        }

        fun add(v: FloatArray) {
            for (i in v.indices) sum[i] += v[i]
            norm = length(sum)
            faces++
        }
    }

    /**
     * Puts the faces that have no person yet into people (see the class comment). With [finalPass] a face that
     * matches nobody well enough becomes a person of its own instead of waiting.
     */
    private suspend fun group(dao: FaceDao, finalPass: Boolean) {
        if (withContext(Dispatchers.IO) { dao.anyUngroupedId() } == null) return
        val people = HashMap<Long, Person>()
        for (grouped in withContext(Dispatchers.IO) { dao.groupedDescriptors() }) {
            val vector = FaceDescriptor.toFloats(grouped.descriptor) ?: continue
            val person = people[grouped.clusterId]
            if (person == null) people[grouped.clusterId] = Person(vector.copyOf()) else person.add(vector)
        }
        var afterId = 0L
        var joined = 0
        var created = 0
        var waiting = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = withContext(Dispatchers.IO) { dao.ungrouped(afterId, GROUP_PAGE) }
            if (page.isEmpty()) break
            withContext(Dispatchers.IO) {
                for (face in page) {
                    afterId = face.id
                    val vector = FaceDescriptor.toFloats(face.descriptor)
                    if (vector == null) {
                        // A signature that cannot be read still is a face: it gets a person of its own.
                        if (finalPass) dao.setCluster(face.id, face.id)
                        continue
                    }
                    val vectorNorm = length(vector)
                    var best = -1f
                    var second = -1f
                    var bestId = 0L
                    for ((id, person) in people) {
                        val similarity = person.similarity(vector, vectorNorm)
                        if (similarity > best) {
                            second = best
                            best = similarity
                            bestId = id
                        } else if (similarity > second) {
                            second = similarity
                        }
                    }
                    when {
                        best >= SAME_PERSON && best - second >= MARGIN -> {
                            people.getValue(bestId).add(vector)
                            dao.setCluster(face.id, bestId)
                            joined++
                        }
                        best < NEW_PERSON || finalPass -> {
                            people[face.id] = Person(vector.copyOf())
                            dao.setCluster(face.id, face.id)
                            created++
                        }
                        else -> waiting++ // doubtful: judged again later
                    }
                }
            }
            if (page.size < GROUP_PAGE) break
        }
        val merged = if (finalPass) mergeSmallPeople(dao, people) else 0
        Log.i(TAG, "grouping: joined=$joined newPeople=$created waiting=$waiting merged=$merged totalPeople=${people.size - merged} final=$finalPass")
    }

    /**
     * A person seen in only one or two photos that clearly matches an established person (three or more faces) by the same
     * rule as grouping belongs to that person: the two are merged, so the same face does not stay in two groups just because
     * it first appeared in a different light. Established people are never merged with each other.
     */
    private fun mergeSmallPeople(dao: FaceDao, people: HashMap<Long, Person>): Int {
        val established = people.filter { it.value.faces >= ESTABLISHED_FACES }
        if (established.isEmpty()) return 0
        var merged = 0
        for ((id, small) in people.entries.filter { it.value.faces < ESTABLISHED_FACES }.map { it.key to it.value }) {
            val norm = length(small.sum)
            if (norm <= 0f) continue
            var best = -1f
            var second = -1f
            var bestId = 0L
            for ((otherId, person) in established) {
                val similarity = person.similarity(small.sum, norm)
                if (similarity > best) {
                    second = best
                    best = similarity
                    bestId = otherId
                } else if (similarity > second) {
                    second = similarity
                }
            }
            if (best >= SAME_PERSON && best - second >= MARGIN) {
                dao.mergePeople(id, bestId)
                merged++
            }
        }
        if (merged > 0) Log.i(TAG, "merged $merged small groups into established people")
        return merged
    }

    private fun length(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }

    private const val DEBOUNCE_MS = 3_000L
    private const val BATCH_SIZE = 12
    private const val EARLY_PHOTOS = 120
    private const val EARLY_GROUP_EVERY = 12
    private const val GROUP_EVERY = 45
    private const val GROUP_PAGE = 500
    private const val LOG_EVERY = 100
    private const val MIN_PHOTOS_FOR_VERDICT = 20
    private const val TARGET_SIDE = 800
    private const val MAX_FACES = 20
    private const val ESTABLISHED_FACES = 3
    private const val MAX_ATTEMPTS = 3
    private const val MAX_AUTO_RETRIES = 3
    private const val RETRY_DELAY_MS = 30_000L
    private const val MIN_DETECT_SIZE = 0.05f
    // Everything the detector finds (it looks for faces from 5% of the picture) is kept, as long as both eyes are visible
    // and the head is not turned too far; the skill's tighter 8% / 30 degrees threw away small faces in group photos.
    private const val MIN_FACE_RATIO = 0.05f
    private const val MAX_YAW = 45f
    private const val MAX_ROLL = 45f

    // Cosine similarity limits: at least SAME_PERSON and clearly ahead of the next person to join; below NEW_PERSON
    // to every known person to start a new one; in between: waits (and becomes its own person when the scan is done).
    private const val SAME_PERSON = 0.80f
    private const val MARGIN = 0.02f
    private const val NEW_PERSON = 0.65f
}
