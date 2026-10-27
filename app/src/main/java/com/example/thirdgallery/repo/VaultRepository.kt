package com.example.thirdgallery.repo

import android.content.Context
import android.provider.OpenableColumns
import com.example.thirdgallery.R
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.MediaEntity
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.helper.VaultPrefs
import com.example.thirdgallery.model.VaultFolderUi
import com.example.thirdgallery.model.VaultSummary

/** The vault's PIN, recovery question and fingerprint setting, and the summary its home screen shows. */
class VaultRepository(context: Context) {

    private val app = context.applicationContext

    val isSetUp: Boolean get() = VaultPrefs.hasPin(app)

    val isFingerprintEnabled: Boolean get() = VaultPrefs.isFingerprintEnabled(app)

    fun createPin(pin: String) = VaultPrefs.savePin(app, pin)

    fun verifyPin(pin: String): Boolean = VaultPrefs.verifyPin(app, pin)

    fun saveRecovery(question: Int, answer: String) = VaultPrefs.saveRecovery(app, question, answer)

    fun setFingerprintEnabled(enabled: Boolean) = VaultPrefs.setFingerprintEnabled(app, enabled)

    /** Number of items kept in the vault, for the Albums tab. */
    fun itemCount(): Int = VaultPrefs.itemUids(app).size

    /** Reads the database and the files, so call it off the main thread. */
    fun loadSummary(): VaultSummary {
        val uids = VaultPrefs.itemUids(app)
        val items = if (uids.isEmpty()) {
            emptyList()
        } else {
            val dao = AppDatabase.getInstance(app).mediaDao()
            val byUid = uids.chunked(CHUNK).flatMap { dao.byUids(it) }.associateBy { it.uid }
            // A vault item that is no longer indexed (deleted elsewhere) simply drops out.
            uids.mapNotNull { byUid[it] }
        }
        val photos = items.filter { it.mediaType != MediaTypes.VIDEO }
        val videos = items.filter { it.mediaType == MediaTypes.VIDEO }
        return VaultSummary(
            total = items.size,
            photos = photos.size,
            videos = videos.size,
            sizeBytes = items.sumOf { sizeOf(it) },
            folders = listOf(
                folder("all", R.string.vault_folder_all, R.drawable.ic_photo_library, items),
                folder("photos", R.string.vault_folder_photos, R.drawable.ic_vault_image, photos),
                folder("videos", R.string.vault_folder_videos, R.drawable.ic_smart_display, videos),
                // Items are filed into these by the vault's "Move" action, so they start empty.
                folder("documents", R.string.vault_folder_documents, R.drawable.ic_folder, emptyList()),
                folder("personal", R.string.vault_folder_personal, R.drawable.ic_folder, emptyList()),
            ),
            recent = items.take(RECENT_COUNT),
        )
    }

    private fun folder(key: String, name: Int, icon: Int, items: List<MediaEntity>) =
        VaultFolderUi(key, name, icon, items.size, items.firstOrNull()?.uid)

    private fun sizeOf(item: MediaEntity): Long = try {
        app.contentResolver.query(
            MediaUris.of(item.mediaId, item.mediaType),
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private companion object {
        const val CHUNK = 500
        const val RECENT_COUNT = 9
    }
}
