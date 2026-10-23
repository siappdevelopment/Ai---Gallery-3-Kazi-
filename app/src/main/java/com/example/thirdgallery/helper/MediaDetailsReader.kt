package com.example.thirdgallery.helper

import android.content.Context
import android.location.Geocoder
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.annotation.StringRes
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One line of the Details dialog. */
class DetailRow(@StringRes val label: Int, val value: String)

/**
 * Reads what the Details dialog shows for one photo or video: date, file name, folder, size and
 * dimensions from MediaStore, and camera and place from the picture's EXIF data. Anything that is
 * missing is simply left out. Blocking: call it off the main thread.
 */
object MediaDetailsReader {

    fun read(context: Context, media: MediaEntity): List<DetailRow> {
        val rows = ArrayList<DetailRow>()
        val locale = Locale.getDefault()
        val date = DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(media.dateMs))
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(media.dateMs))
        rows.add(DetailRow(R.string.details_date, "$date · $time"))

        val uri = MediaUris.of(media.mediaId, media.mediaType)
        try {
            queryFile(context, media)?.let { file ->
                if (file.name.isNotBlank()) rows.add(DetailRow(R.string.details_name, file.name))
                if (file.path.isNotBlank()) rows.add(DetailRow(R.string.details_path, file.path))
                val size = file.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
                val dimensions = if (file.width > 0 && file.height > 0) "${file.width} × ${file.height}" else null
                listOfNotNull(size, dimensions).joinToString(" · ").takeIf { it.isNotEmpty() }
                    ?.let { rows.add(DetailRow(R.string.details_size, it)) }
            }
        } catch (e: SecurityException) {
            // No access to the file details: show what the index already knows.
        }

        if (media.mediaType == MediaTypes.IMAGE) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    camera(exif)?.let { rows.add(DetailRow(R.string.details_camera, it)) }
                    place(context, exif, locale)?.let { rows.add(DetailRow(R.string.details_location, it)) }
                }
            } catch (e: IOException) {
                // Not a picture with EXIF data (or unreadable): no camera or location rows.
            } catch (e: SecurityException) {
                // The file cannot be opened: no camera or location rows.
            }
        }
        return rows
    }

    private class FileInfo(val name: String, val path: String, val size: Long, val width: Int, val height: Int)

    private fun queryFile(context: Context, media: MediaEntity): FileInfo? {
        val uri = MediaUris.of(media.mediaId, media.mediaType)
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.MediaColumns.DATA
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            pathColumn,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val raw = c.getString(4).orEmpty()
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) raw.trimEnd('/') else File(raw).parent.orEmpty()
            return FileInfo(c.getString(0).orEmpty(), path, c.getLong(1), c.getInt(2), c.getInt(3))
        }
        return null
    }

    private fun camera(exif: ExifInterface): String? {
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { "f/" + trim(it) }
        val shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.takeIf { it > 0 }?.let {
            if (it < 1) "1/" + (1 / it).roundToInt() + "s" else trim(it) + "s"
        }
        return listOf(model, aperture.orEmpty(), shutter.orEmpty()).filter { it.isNotEmpty() }
            .joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.1f", value)

    /** City and region from the picture's GPS position; the plain coordinates when no address is found. */
    private fun place(context: Context, exif: ExifInterface, locale: Locale): String? {
        val position = FloatArray(2)
        if (!exif.getLatLong(position)) return null
        val coordinates = String.format(Locale.US, "%.4f, %.4f", position[0], position[1])
        if (!Geocoder.isPresent()) return coordinates
        return try {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, locale).getFromLocation(position[0].toDouble(), position[1].toDouble(), 1)?.firstOrNull()
            val parts = listOfNotNull(address?.locality ?: address?.subAdminArea, address?.adminArea, address?.countryName)
                .distinct().take(2)
            if (parts.isEmpty()) coordinates else parts.joinToString(", ")
        } catch (e: IOException) {
            coordinates
        }
    }
}
