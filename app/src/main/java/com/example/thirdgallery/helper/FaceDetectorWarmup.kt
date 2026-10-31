package com.example.thirdgallery.helper

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.TimeUnit

/**
 * Loads the native model of an ML Kit face detector, one detector at a time for the whole app.
 *
 * ML Kit loads a detector's model lazily on its executor thread at the first `process()`. Several detectors (the people scan runs
 * 2-3, the smile scan one more) that did this at the same moment ran `libface_detector_v2_jni`'s `initDetectorJni` in parallel,
 * and that native initialisation is not safe to run concurrently: the process died with a native crash on a `pool-N-thread-1`
 * worker. Every detector is warmed up here under one lock (and awaited) before it is used, so the initialisations never overlap;
 * normal detection afterwards runs in parallel as before.
 */
internal object FaceDetectorWarmup {

    private val lock = Any()
    private const val SIDE = 64
    private const val TIMEOUT_SECONDS = 30L

    /** Blocking, never on the main thread. True when the detector's model is loaded; false when it could not be (then try later). */
    fun warmUp(detector: FaceDetector): Boolean = synchronized(lock) {
        var bitmap: Bitmap? = null
        try {
            bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        } finally {
            bitmap?.recycle()
        }
    }
}
