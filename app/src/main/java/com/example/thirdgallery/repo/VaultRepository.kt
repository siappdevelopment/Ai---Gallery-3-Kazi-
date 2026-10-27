package com.example.thirdgallery.repo

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import com.example.thirdgallery.R
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.helper.VaultFiles
import com.example.thirdgallery.helper.VaultPrefs
import com.example.thirdgallery.model.VaultFolderUi
import com.example.thirdgallery.model.VaultSummary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault: its PIN, recovery question and fingerprint setting, and the items kept in it.
 *
 * Moving an item in is a two-step, loss-proof process (everything here is blocking, call it off the main
 * thread): [stage] copies the file into the private vault folder and records it as not yet committed; the
 * caller then asks the system to delete the original, and either [commit]s (the original is gone, the item
 * shows in the vault and leaves the gallery index) or [rollback]s (nothing changed, the copy is removed).
 */
class VaultRepository(context: Context) {

    /** A copy waiting for the original to be deleted. */
    class Staged(val id: Long, val sourceUid: Long)

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val database get() = AppDatabase.getInstance(app)
    private val vaultDao get() = database.vaultDao()

    val isSetUp: Boolean get() = VaultPrefs.hasPin(app)

    val isFingerprintEnabled: Boolean get() = VaultPrefs.isFingerprintEnabled(app)

    fun createPin(pin: String) = VaultPrefs.savePin(app, pin)

    fun verifyPin(pin: String): Boolean = VaultPrefs.verifyPin(app, pin)

    fun saveRecovery(question: Int, answer: String) = VaultPrefs.saveRecovery(app, question, answer)

    fun setFingerprintEnabled(enabled: Boolean) = VaultPrefs.setFingerprintEnabled(app, enabled)

    fun observeItems(): LiveData<List<VaultItemEntity>> = vaultDao.observeItems()

    /** Number of items kept in the vault, for the Albums tab. */
    fun observeCount(): LiveData<Int> = vaultDao.observeCount()

    /** Counts, folders and newest items of [items] (newest first), for the vault home. */
    fun summaryOf(items: List<VaultItemEntity>): VaultSummary {
        val photos = items.filter { it.mediaType != MediaTypes.VIDEO }
        val videos = items.filter { it.mediaType == MediaTypes.VIDEO }
        return VaultSummary(
            total = items.size,
            photos = photos.size,
            videos = videos.size,
            sizeBytes = items.sumOf { it.sizeBytes },
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

    private fun folder(key: String, name: Int, icon: Int, items: List<VaultItemEntity>) =
        VaultFolderUi(key, name, icon, items.size, items.firstOrNull()?.fileName)

    /** Step 1: copies the original into the vault folder. Null when it could not be copied (nothing is left behind). */
    fun stage(uid: Long): Staged? {
        // One move per item at a time, so a double tap can never create two copies.
        if (inFlight.putIfAbsent(uid, true) != null) return null
        var temp: File? = null
        var target: File? = null
        try {
            val media = database.mediaDao().byUid(uid) ?: return release(uid)
            val source = MediaUris.ofUid(uid)
            val info = resolver.query(
                source,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)) else null
            } ?: return release(uid)

            val name = UUID.randomUUID().toString()
            temp = File(VaultFiles.dir(app), "$name$PART")
            target = File(VaultFiles.dir(app), name)
            val copied = (resolver.openInputStream(source) ?: throw IOException("no input")).use { input ->
                FileOutputStream(temp).use { out ->
                    val bytes = input.copyTo(out)
                    out.fd.sync()
                    bytes
                }
            }
            // A short copy would lose data once the original is deleted.
            if (info.third > 0 && copied != info.third) throw IOException("incomplete copy")
            if (!temp.renameTo(target)) throw IOException("rename failed")

            val id = vaultDao.insert(
                VaultItemEntity(
                    fileName = name,
                    mediaType = media.mediaType,
                    displayName = info.first,
                    mimeType = info.second,
                    dateMs = media.dateMs,
                    durationMs = media.durationMs,
                    sizeBytes = copied,
                    isFavorite = media.isFavorite,
                    addedAt = System.currentTimeMillis(),
                    sourceUid = uid,
                    committed = false,
                ),
            )
            return Staged(id, uid)
        } catch (e: IOException) {
            discard(temp, target)
        } catch (e: SecurityException) {
            discard(temp, target)
        } catch (e: IllegalStateException) {
            discard(temp, target)
        } catch (e: RuntimeException) {
            discard(temp, target)
        }
        return release(uid)
    }

    private fun release(uid: Long): Staged? {
        inFlight.remove(uid)
        return null
    }

    private fun discard(vararg files: File?) {
        files.forEach { it?.delete() }
    }

    /**
     * Step 2a: the system deleted the original. Shows the item in the vault and takes it out of the gallery
     * index, albums and favorites in one transaction. False (and nothing changed) when the original is still there.
     */
    fun commit(staged: Staged): Boolean {
        try {
            if (exists(staged.sourceUid)) return false
            database.runInTransaction(Runnable {
                vaultDao.markCommitted(staged.id)
                database.mediaDao().deleteByUid(staged.sourceUid)
                database.albumDao().deleteItemEverywhere(staged.sourceUid)
            })
            return true
        } finally {
            inFlight.remove(staged.sourceUid)
        }
    }

    /** Step 2b: the move was cancelled or failed. The original is untouched, so the copy goes away. */
    fun rollback(staged: Staged) {
        try {
            removeRow(vaultDao.byId(staged.id))
        } finally {
            inFlight.remove(staged.sourceUid)
        }
    }

    /**
     * Settles moves that were interrupted (the app was killed while the system dialog was open): a copy whose
     * original is gone is committed, one whose original is still there after a long time is removed. Recent
     * ones are left alone because the user may still be answering the dialog. Rows whose file has gone
     * missing (for example restored from a backup) are dropped.
     */
    fun settleInterrupted() {
        val now = System.currentTimeMillis()
        for (row in vaultDao.pending()) {
            if (inFlight.containsKey(row.sourceUid)) continue
            if (!exists(row.sourceUid)) {
                database.runInTransaction(Runnable {
                    vaultDao.markCommitted(row.id)
                    database.mediaDao().deleteByUid(row.sourceUid)
                    database.albumDao().deleteItemEverywhere(row.sourceUid)
                })
            } else if (now - row.addedAt > STALE_MS) {
                removeRow(row)
            }
        }
        for (row in vaultDao.committed()) {
            if (!VaultFiles.file(app, row.fileName).exists()) vaultDao.delete(row.id)
        }
    }

    private fun removeRow(row: VaultItemEntity?) {
        if (row == null) return
        VaultFiles.file(app, row.fileName).delete()
        vaultDao.delete(row.id)
    }

    /** Whether the original is still in MediaStore; an unreadable answer counts as "still there" (never lose data). */
    private fun exists(uid: Long): Boolean = try {
        resolver.query(MediaUris.ofUid(uid), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: true
    } catch (e: SecurityException) {
        true
    }

    private companion object {
        const val RECENT_COUNT = 9
        const val PART = ".part"
        const val STALE_MS = 24L * 60 * 60 * 1000

        /** Originals currently being moved (source uid), in this process only. */
        val inFlight = ConcurrentHashMap<Long, Boolean>()
    }
}
