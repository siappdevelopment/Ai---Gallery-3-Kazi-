package com.example.thirdgallery.helper

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.example.thirdgallery.R
import com.example.thirdgallery.database.MediaTypes
import java.util.concurrent.Executors

/**
 * Shares photos and videos with the Android Sharesheet. The items are MediaStore `content://`
 * URIs; the receiving app is given temporary read access (clip data + FLAG_GRANT_READ_URI_PERMISSION),
 * so no file copies or FileProvider are needed.
 */
object ShareHelper {

    /** Binder transactions are limited to about 1 MB, so very large days are cut to this many items. */
    private const val MAX_ITEMS = 500

    private val executor = Executors.newSingleThreadExecutor()

    /** Builds the chooser off the main thread (MIME lookups) and starts it. [onFailed] runs on the main thread. */
    fun share(activity: Activity, uids: List<Long>, onFailed: () -> Unit) {
        val items = uids.take(MAX_ITEMS)
        if (items.isEmpty()) {
            onFailed()
            return
        }
        executor.execute {
            val chooser = try {
                buildChooser(activity, items)
            } catch (e: Exception) {
                null
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (chooser == null) {
                    onFailed()
                    return@runOnUiThread
                }
                try {
                    activity.startActivity(chooser)
                } catch (e: Exception) {
                    onFailed()
                }
            }
        }
    }

    private fun buildChooser(activity: Activity, uids: List<Long>): Intent {
        val resolver = activity.contentResolver
        val uris: List<Uri> = uids.map { MediaUris.ofUid(it) }
        // Exact type from MediaStore, falling back to the type the id tells us (video / image).
        val mimeTypes = uris.mapIndexed { index, uri ->
            resolver.getType(uri)
                ?: if (MediaTypes.typeOf(uids[index]) == MediaTypes.VIDEO) "video/*" else "image/*"
        }

        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
        send.type = commonType(mimeTypes)
        if (uris.size == 1) {
            send.putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // Clip data is what actually carries the temporary read grant for every URI.
        val clip = ClipData.newUri(resolver, "media", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return Intent.createChooser(send, activity.getString(R.string.photo_share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** One exact type for a single item; when all types share a family, that family with a wildcard subtype; else a full wildcard. */
    private fun commonType(types: List<String>): String {
        if (types.size == 1) return types[0]
        val tops = types.map { it.substringBefore('/') }.toSet()
        return if (tops.size == 1) "${tops.first()}/*" else "*/*"
    }
}
