package com.photovideo.gallerykz.memories.helper

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.photovideo.gallerykz.memories.database.MediaEntity
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Measures one photo for the "Made for you" categories, entirely on the device: how sharp it is (variance of the
 * Laplacian of the brightness, on a small copy), how well exposed it is (average brightness and how much is
 * burnt out or black), how large it is (pixels and bytes per pixel, i.e. the real compression quality), and
 * whether it was edited. Nothing is guessed: a value that cannot be read simply does not count.
 */
object PhotoQuality {

    /** Bump when the rules below change, so every photo is measured again. */
    const val VERSION = 1

    class Result(
        val sharpness: Int,
        val exposure: Int,
        val score: Int,
        val width: Int,
        val height: Int,
        val editedAtMs: Long,
    )

    private class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

    /** Null when the photo cannot be read or decoded (the caller records a failed attempt). */
    fun analyze(context: Context, media: MediaEntity): Result? {
        val resolver = context.contentResolver
        val uri = MediaUris.of(media.mediaId, media.mediaType)
        val decoded = decode(resolver, uri) ?: return null
        val (sharpness, exposure, pixels) = try {
            val metrics = measure(decoded.bitmap)
            Triple(
                (sharpnessShare(metrics.laplacianVariance) * 100).roundToInt(),
                (exposureShare(metrics.meanLuma, metrics.clippedShare) * 100).roundToInt(),
                decoded.width * decoded.height,
            )
        } finally {
            decoded.bitmap.recycle()
        }
        val resolution = resolutionShare(pixels, fileSize(resolver, uri))
        val score = ((0.5 * sharpness + 0.3 * exposure + 0.2 * resolution * 100)).roundToInt().coerceIn(0, 100)
        return Result(sharpness, exposure, score, decoded.width, decoded.height, editedAt(resolver, uri, media))
    }

    // ---- decoding: a copy of about 400-800 px on the long side is plenty for the measurements ----

    private fun decode(resolver: ContentResolver, uri: Uri): Decoded? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var width = 0
            var height = 0
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize(max(width, height)))
            }
            Decoded(bitmap, width, height)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight)) }
                val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (bitmap == null) null else Decoded(bitmap, bounds.outWidth, bounds.outHeight)
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun sampleSize(longSide: Int): Int =
        if (longSide <= TARGET_SIDE * 2) 1 else Integer.highestOneBit(longSide / TARGET_SIDE).coerceAtLeast(1)

    // ---- measurements ----

    private class Metrics(val laplacianVariance: Double, val meanLuma: Double, val clippedShare: Double)

    private fun measure(bitmap: Bitmap): Metrics {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(w * h)
        var sum = 0L
        var clipped = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            luma[i] = y
            sum += y
            if (y <= 5 || y >= 250) clipped++
        }
        var lapSum = 0.0
        var lapSquares = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]).toDouble()
                lapSum += lap
                lapSquares += lap * lap
                n++
            }
        }
        val variance = if (n == 0) 0.0 else lapSquares / n - (lapSum / n) * (lapSum / n)
        return Metrics(variance, sum.toDouble() / pixels.size, clipped.toDouble() / pixels.size)
    }

    /** 0..1 on a log scale: variance 25 (soft) is 0, 250 (crisp) is 1. */
    private fun sharpnessShare(variance: Double): Double =
        ((ln(variance + 1.0) - ln(SOFT_VARIANCE)) / (ln(CRISP_VARIANCE) - ln(SOFT_VARIANCE))).coerceIn(0.0, 1.0)

    /** 1 for a normal brightness (70..185), falling off to 0 for very dark or burnt-out pictures; lots of clipping costs more. */
    private fun exposureShare(mean: Double, clippedShare: Double): Double {
        val base = when {
            mean < 70.0 -> (mean - 25.0) / 45.0
            mean > 185.0 -> (235.0 - mean) / 50.0
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        val penalty = (1.0 - max(0.0, clippedShare - 0.15) * 2.0).coerceIn(0.0, 1.0)
        return base * penalty
    }

    /** 0..1: megapixels (1 MP = 0, 8 MP = 1) and how much data each pixel got (a heavily compressed copy scores low). */
    private fun resolutionShare(pixels: Int, bytes: Long): Double {
        val megapixels = pixels / 1_000_000.0
        val byPixels = ((megapixels - 1.0) / 7.0).coerceIn(0.0, 1.0)
        if (bytes <= 0 || pixels <= 0) return byPixels
        val bytesPerPixel = bytes.toDouble() / pixels
        val byBytes = ((bytesPerPixel - 0.06) / 0.24).coerceIn(0.0, 1.0)
        return 0.6 * byPixels + 0.4 * byBytes
    }

    private fun fileSize(resolver: ContentResolver, uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: IOException) {
        -1L
    } catch (e: SecurityException) {
        -1L
    }

    // ---- edits ----

    /**
     * When the photo was edited, or 0 when nothing shows an edit. An edit shows as one of: the picture data names an
     * editor (EXIF Software), the picture was changed at least two minutes after it was taken (EXIF DateTime against
     * DateTimeOriginal; cameras write both at once), or its file name or folder says so ("edited", Snapseed, PicsArt...).
     * Being recently added or recently modified alone never counts. The time is the file's modified time.
     */
    private fun editedAt(resolver: ContentResolver, uri: Uri, media: MediaEntity): Long {
        var edited = EDIT_NAME.containsMatchIn(media.displayName) || EDIT_NAME.containsMatchIn(media.bucketName)
        var exifChanged = 0L
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty()
                if (EDIT_SOFTWARE.containsMatchIn(software)) edited = true
                val taken = parseExifTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                val changed = parseExifTime(exif.getAttribute(ExifInterface.TAG_DATETIME))
                if (taken != null && changed != null && changed - taken >= EDIT_GAP_MS) {
                    edited = true
                    exifChanged = changed
                }
            }
        } catch (e: IOException) {
            // No EXIF data (for example a PNG): only the name and the folder can tell.
        } catch (e: SecurityException) {
            // The file cannot be opened any more: only the name and the folder can tell.
        }
        if (!edited) return 0L
        return media.modifiedMs.takeIf { it > 0 } ?: exifChanged
    }

    private fun parseExifTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value.trim())?.time
        } catch (e: ParseException) {
            null
        }
    }

    private const val TARGET_SIDE = 400
    private const val SOFT_VARIANCE = 25.0
    private const val CRISP_VARIANCE = 250.0
    private const val EDIT_GAP_MS = 120_000L

    /** Whole-word matches only: "Nooedl Edit", "IMG_1_edited" and the "Edits" folder match, "Credits" does not. */
    private val EDIT_NAME = Regex(
        "(?i)(^|[^a-z])(edit|edited|edits|snapseed|picsart|lightroom|photoshop|vsco|pixlr|canva|photoroom|facetune|polarr)([^a-z]|$)"
    )

    private val EDIT_SOFTWARE = Regex(
        "(?i)(snapseed|lightroom|photoshop|picsart|vsco|pixlr|canva|photoroom|facetune|polarr|afterlight|gimp|adobe|edit)"
    )
}
