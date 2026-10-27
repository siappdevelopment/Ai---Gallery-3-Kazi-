package com.example.thirdgallery.repo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import com.example.thirdgallery.R
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.MediaTypes
import com.example.thirdgallery.database.VaultAlertEntity
import com.example.thirdgallery.database.VaultFolderEntity
import com.example.thirdgallery.database.VaultItemEntity
import com.example.thirdgallery.helper.MediaUris
import com.example.thirdgallery.helper.VaultFiles
import com.example.thirdgallery.helper.VaultPrefs
import com.example.thirdgallery.model.VaultFolderKey
import com.example.thirdgallery.model.VaultFolderUi
import com.example.thirdgallery.model.VaultSummary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault: its PIN, recovery question and settings, its folders, break-in alerts and the items kept in it.
 *
 * Moving an item in is a two-step, loss-proof process (everything here is blocking, call it off the main
 * thread): [stage] copies the file into the private vault folder and records it as not yet committed; the
 * caller then asks the system to delete the original, and either [commit]s (the original is gone, the item
 * shows in the vault and leaves the gallery index) or [rollback]s (nothing changed, the copy is removed).
 * Taking items out ([restore]) is the same idea reversed: the file is written back to MediaStore first and
 * only then removed from the vault.
 */
class VaultRepository(context: Context) {

    /** A copy waiting for the original to be deleted. */
    class Staged(val id: Long, val sourceUid: Long)

    enum class FolderResult { CREATED, EMPTY, EXISTS }

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val database get() = AppDatabase.getInstance(app)
    private val vaultDao get() = database.vaultDao()

    // ---- security settings

    val isSetUp: Boolean get() = VaultPrefs.hasPin(app)

    val isFingerprintEnabled: Boolean get() = VaultPrefs.isFingerprintEnabled(app)

    val hasRecovery: Boolean get() = VaultPrefs.hasRecovery(app)

    /** Index of the recovery question, -1 when there is none. */
    val recoveryQuestion: Int get() = VaultPrefs.recoveryQuestion(app)

    var autoLock: Int
        get() = VaultPrefs.autoLock(app)
        set(value) = VaultPrefs.setAutoLock(app, value)

    var alertsEnabled: Boolean
        get() = VaultPrefs.isAlertsEnabled(app)
        set(value) = VaultPrefs.setAlertsEnabled(app, value)

    fun createPin(pin: String) = VaultPrefs.savePin(app, pin)

    fun verifyPin(pin: String): Boolean = VaultPrefs.verifyPin(app, pin)

    fun verifyAnswer(answer: String): Boolean = VaultPrefs.verifyAnswer(app, answer)

    fun saveRecovery(question: Int, answer: String) = VaultPrefs.saveRecovery(app, question, answer)

    fun setFingerprintEnabled(enabled: Boolean) = VaultPrefs.setFingerprintEnabled(app, enabled)

    // ---- live data for the screens

    fun observeItems(): LiveData<List<VaultItemEntity>> = vaultDao.observeItems()

    fun observeFolders(): LiveData<List<VaultFolderEntity>> = vaultDao.observeFolders()

    fun observeAlerts(): LiveData<List<VaultAlertEntity>> = vaultDao.observeAlerts()

    /** Number of items kept in the vault, for the Albums tab. */
    fun observeCount(): LiveData<Int> = vaultDao.observeCount()

    /** Counts, folders and newest items of [items] (newest first), for the vault home. */
    fun summaryOf(items: List<VaultItemEntity>, folders: List<VaultFolderEntity>): VaultSummary {
        val photos = filter(items, VaultFolderKey.PHOTOS)
        val videos = filter(items, VaultFolderKey.VIDEOS)
        return VaultSummary(
            total = items.size,
            photos = photos.size,
            videos = videos.size,
            sizeBytes = items.sumOf { it.sizeBytes },
            folders = buildList {
                add(builtIn(VaultFolderKey.ALL, R.string.vault_folder_all, R.drawable.ic_photo_library, items))
                add(builtIn(VaultFolderKey.PHOTOS, R.string.vault_folder_photos, R.drawable.ic_vault_image, photos))
                add(builtIn(VaultFolderKey.VIDEOS, R.string.vault_folder_videos, R.drawable.ic_smart_display, videos))
                folders.forEach { f ->
                    val inside = items.filter { it.folderId == f.id }
                    add(VaultFolderUi(VaultFolderKey.user(f.id), 0, f.name, R.drawable.ic_folder, inside.size, inside.firstOrNull()?.fileName))
                }
            },
            recent = items.take(RECENT_COUNT),
            ids = items.map { it.id },
        )
    }

    /** The items a folder screen shows for [key] (newest first, like [items]). */
    fun filter(items: List<VaultItemEntity>, key: String): List<VaultItemEntity> = when (key) {
        VaultFolderKey.PHOTOS -> items.filter { it.mediaType != MediaTypes.VIDEO }
        VaultFolderKey.VIDEOS -> items.filter { it.mediaType == MediaTypes.VIDEO }
        else -> VaultFolderKey.userId(key)?.let { id -> items.filter { it.folderId == id } } ?: items
    }

    private fun builtIn(key: String, name: Int, icon: Int, items: List<VaultItemEntity>) =
        VaultFolderUi(key, name, null, icon, items.size, items.firstOrNull()?.fileName)

    // ---- folders

    /** The vault starts with Documents and Personal, named in the language of the moment. Touches the database: not on the main thread. */
    fun ensureDefaultFolders() {
        if (VaultPrefs.areFoldersSeeded(app)) return
        val now = System.currentTimeMillis()
        if (vaultDao.folders().isEmpty()) {
            vaultDao.insertFolder(VaultFolderEntity(name = app.getString(R.string.vault_folder_documents), createdAt = now))
            vaultDao.insertFolder(VaultFolderEntity(name = app.getString(R.string.vault_folder_personal), createdAt = now + 1))
        }
        VaultPrefs.setFoldersSeeded(app, true)
    }

    fun createFolder(rawName: String): FolderResult {
        val name = rawName.trim()
        if (name.isEmpty()) return FolderResult.EMPTY
        if (vaultDao.folders().any { it.name.equals(name, ignoreCase = true) }) return FolderResult.EXISTS
        vaultDao.insertFolder(VaultFolderEntity(name = name, createdAt = System.currentTimeMillis()))
        return FolderResult.CREATED
    }

    fun moveToFolder(ids: List<Long>, folderId: Long) {
        ids.chunked(CHUNK).forEach { vaultDao.setFolder(it, folderId) }
    }

    // ---- break-in alerts

    /** Logs the wrong PIN when alerts are on. */
    fun logWrongPin(attempt: Int) {
        if (!alertsEnabled) return
        vaultDao.insertAlert(VaultAlertEntity(attemptedAt = System.currentTimeMillis(), attempt = attempt))
    }

    fun clearAlerts() = vaultDao.clearAlerts()

    // ---- moving items in

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

    /** Stages every uid in turn; [onProgress] gets the number handled so far. Failed ones are simply left out. */
    fun stageAll(uids: List<Long>, onProgress: (Int) -> Unit): List<Staged> {
        val staged = ArrayList<Staged>(uids.size)
        uids.forEachIndexed { index, uid ->
            stage(uid)?.let { staged.add(it) }
            onProgress(index + 1)
        }
        return staged
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

    /** Commits every item the system deleted; the rest are rolled back. Returns how many are now in the vault. */
    fun commitAll(staged: List<Staged>): Int {
        var done = 0
        for (s in staged) {
            // An original that is still there (the user refused part of it) means that copy must not stay.
            if (commit(s)) done++ else rollback(s)
        }
        return done
    }

    /** Step 2b: the move was cancelled or failed. The original is untouched, so the copy goes away. */
    fun rollback(staged: Staged) {
        try {
            removeRow(vaultDao.byId(staged.id))
        } finally {
            inFlight.remove(staged.sourceUid)
        }
    }

    fun rollbackAll(staged: List<Staged>) = staged.forEach { rollback(it) }

    /**
     * Settles moves that were interrupted (the app was killed while the system dialog was open): a copy whose
     * original is gone is committed, one whose original is still there after a long time is removed. Recent
     * ones are left alone because the user may still be answering the dialog. Rows whose file has gone
     * missing (for example restored from a backup) are dropped.
     */
    fun settleInterrupted() {
        ensureDefaultFolders()
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

    // ---- taking items out

    /**
     * Puts the items back into the gallery (MediaStore) and removes them from the vault. Each item is written
     * first and removed from the vault only when that worked, so nothing is lost; returns how many failed.
     */
    fun restore(ids: List<Long>): Int {
        var failed = 0
        for (row in ids.chunked(CHUNK).flatMap { vaultDao.byIds(it) }) {
            if (restoreOne(row)) removeRow(row) else failed++
        }
        return failed
    }

    private fun restoreOne(item: VaultItemEntity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val file = VaultFiles.file(app, item.fileName)
        if (!file.exists()) return false
        val isVideo = item.mediaType == MediaTypes.VIDEO
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/" else "Pictures/")
            if (item.dateMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, item.dateMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var target: Uri? = null
        return try {
            val dest = resolver.insert(collection, values) ?: return false
            target = dest
            file.inputStream().use { input ->
                (resolver.openOutputStream(dest) ?: throw IOException("no output")).use { out -> input.copyTo(out) }
            }
            resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0
        } catch (e: IOException) {
            dropTarget(target)
            false
        } catch (e: SecurityException) {
            dropTarget(target)
            false
        } catch (e: IllegalArgumentException) {
            dropTarget(target)
            false
        } catch (e: IllegalStateException) {
            dropTarget(target)
            false
        }
    }

    private fun dropTarget(target: Uri?) {
        if (target == null) return
        try {
            resolver.delete(target, null, null)
        } catch (e: SecurityException) {
            // The half-written entry is cleaned up by the system.
        }
    }

    /** Removes the items for good (they skip the Bin). */
    fun deleteForever(ids: List<Long>) {
        for (row in ids.chunked(CHUNK).flatMap { vaultDao.byIds(it) }) removeRow(row)
    }

    /**
     * Reset vault: every item goes back to the gallery, then the PIN, fingerprint, recovery question,
     * settings, folders and alerts are removed. False (and the vault is kept as it was) when an item
     * could not be restored.
     */
    fun resetVault(): Boolean {
        val ids = vaultDao.committed().map { it.id }
        if (restore(ids) > 0) return false
        vaultDao.clearFolders()
        vaultDao.clearAlerts()
        VaultPrefs.clearAll(app)
        return true
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
        const val CHUNK = 500
        const val PART = ".part"
        const val STALE_MS = 24L * 60 * 60 * 1000

        /** Originals currently being moved (source uid), in this process only. */
        val inFlight = ConcurrentHashMap<Long, Boolean>()
    }
}
