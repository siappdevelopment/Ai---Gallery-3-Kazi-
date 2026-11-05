package com.photovideo.gallerykz.memories.helper

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.photovideo.gallerykz.memories.R
import com.photovideo.gallerykz.memories.database.MediaTypes
import java.util.concurrent.Executors

/**
 * Shares photos and videos with the Android Sharesheet. The items are MediaStore `content://`
 * URIs; the receiving app is given temporary read access (clip data + FLAG_GRANT_READ_URI_PERMISSION),
 * so no file copies or FileProvider are needed.
 */
object ShareHelper {

    /**
     * One share carries at most this many items. The limit keeps the binder transaction small (about 300 bytes per item, 1 MB
     * at most) and the wait short: the system spends about 3 ms per item checking them, so 500 items open in about 1.5 s
     * and all of a 1,600-item library would take 5 s. The newest items are sent (the selection is newest first).
     */
    private const val MAX_ITEMS = 500

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Builds the chooser at once and starts it. [onFailed] runs on the main thread.
     *
     * Building needs no call to another process (see [buildChooser]), so it is instant even for hundreds of items; it used to ask
     * MediaStore for the type of every item first, which took over a second. The system then checks every URI of the intent inside
     * startActivity and that call blocks, so it runs on a worker thread: the screen keeps responding while the chooser comes up.
     */
    fun share(activity: Activity, uids: List<Long>, onFailed: () -> Unit) {
        val items = uids.take(MAX_ITEMS)
        if (items.isEmpty()) {
            onFailed()
            return
        }
        // The system needs a moment per item to open the chooser (about 1.5 s for 500): a second tap meanwhile would queue
        // a second chooser on top of the first.
        if (starting) return
        val chooser = try {
            buildChooser(activity, items)
        } catch (e: Exception) {
            null
        }
        if (chooser == null) {
            onFailed()
            return
        }
        // More items were chosen than fit in one share: say so instead of silently sending only the first ones.
        if (uids.size > items.size) {
            Toast.makeText(
                activity.applicationContext,
                activity.getString(R.string.share_limit_notice, items.size, uids.size),
                Toast.LENGTH_LONG,
            ).show()
        }
        starting = true
        executor.execute {
            try {
                activity.startActivity(chooser)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) onFailed()
                }
            } finally {
                starting = false
            }
        }
    }

    @Volatile
    private var starting = false

    private fun buildChooser(activity: Activity, uids: List<Long>): Intent {
        val uris: List<Uri> = uids.map { MediaUris.ofUid(it) }
        // The type comes from the id (photo / video), never from MediaStore: each getType() is a call to another process and
        // hundreds of them were the delay before the Sharesheet opened. A single item still gets its exact type (one call).
        val families = uids.map { if (MediaTypes.typeOf(it) == MediaTypes.VIDEO) "video" else "image" }
        val type = if (uris.size == 1) {
            activity.contentResolver.getType(uris[0]) ?: "${families[0]}/*"
        } else {
            commonType(families)
        }

        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
        send.type = type
        if (uris.size == 1) {
            send.putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // Clip data is what actually carries the temporary read grant for every URI. It is built from the type above, so
        // it needs no lookup either (ClipData.newUri asks the provider for the type of the first item).
        val clip = ClipData("media", arrayOf(type), ClipData.Item(uris[0]))
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return Intent.createChooser(send, activity.getString(R.string.photo_share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** When every item is in the same family (photo / video), that family with a wildcard subtype; else a full wildcard. */
    private fun commonType(families: List<String>): String {
        val first = families[0]
        return if (families.all { it == first }) "$first/*" else "*/*"
    }
}
