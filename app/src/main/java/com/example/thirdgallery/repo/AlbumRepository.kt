package com.example.thirdgallery.repo

import android.content.Context
import androidx.lifecycle.LiveData
import com.example.thirdgallery.database.AlbumCardRow
import com.example.thirdgallery.database.AlbumDao
import com.example.thirdgallery.database.AlbumEntity
import com.example.thirdgallery.database.AlbumItemEntity
import com.example.thirdgallery.database.AppDatabase
import com.example.thirdgallery.database.UserAlbumRow
import java.util.concurrent.Executors

/** App albums ("Add to album"): create an album and put photos/videos in it. */
class AlbumRepository(context: Context) {

    enum class Failure { EMPTY_NAME, NAME_EXISTS, FAILED }

    /** [failure] is null on success; [albumName] is the album that received the items. */
    data class Result(val albumName: String, val failure: Failure? = null)

    private val database = AppDatabase.getInstance(context.applicationContext)
    private val dao: AlbumDao = database.albumDao()

    val albums: LiveData<List<UserAlbumRow>> = dao.observeAlbums()

    /** App albums as Albums-tab cards (size and four newest items). */
    val albumCards: LiveData<List<AlbumCardRow>> = dao.observeAlbumCards()

    /** Adds [uids] to [album]; with [moveFrom] > 0 they are taken out of that app album in the same transaction (Move). */
    fun addToAlbum(album: UserAlbumRow, uids: List<Long>, onDone: (Result) -> Unit, moveFrom: Long = 0L) {
        executor.execute {
            val result = try {
                val now = System.currentTimeMillis()
                database.runInTransaction(Runnable {
                    dao.insertItems(uids.map { AlbumItemEntity(album.id, it, now) })
                    if (moveFrom > 0 && moveFrom != album.id) removeFrom(moveFrom, uids)
                })
                Result(album.name)
            } catch (e: Exception) {
                Result(album.name, Failure.FAILED)
            }
            onDone(result)
        }
    }

    fun createAlbum(rawName: String, uids: List<Long>, onDone: (Result) -> Unit, moveFrom: Long = 0L) {
        val name = rawName.trim().take(MAX_NAME)
        if (name.isEmpty()) {
            onDone(Result(name, Failure.EMPTY_NAME))
            return
        }
        executor.execute {
            val result = try {
                if (dao.countByName(name) > 0) {
                    Result(name, Failure.NAME_EXISTS)
                } else {
                    val now = System.currentTimeMillis()
                    database.runInTransaction(Runnable {
                        val id = dao.insertAlbum(AlbumEntity(name = name, createdAt = now))
                        dao.insertItems(uids.map { AlbumItemEntity(id, it, now) })
                        if (moveFrom > 0) removeFrom(moveFrom, uids)
                    })
                    Result(name)
                }
            } catch (e: Exception) {
                Result(name, Failure.FAILED)
            }
            onDone(result)
        }
    }

    private fun removeFrom(albumId: Long, uids: List<Long>) {
        uids.chunked(REMOVE_CHUNK).forEach { dao.deleteItems(albumId, it) }
    }

    private companion object {
        const val REMOVE_CHUNK = 500
        const val MAX_NAME = 60
        val executor = Executors.newSingleThreadExecutor()
    }
}
