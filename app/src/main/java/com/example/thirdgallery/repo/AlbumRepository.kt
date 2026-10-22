package com.example.thirdgallery.repo

import android.content.Context
import androidx.lifecycle.LiveData
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

    fun addToAlbum(album: UserAlbumRow, uids: List<Long>, onDone: (Result) -> Unit) {
        executor.execute {
            val result = try {
                val now = System.currentTimeMillis()
                dao.insertItems(uids.map { AlbumItemEntity(album.id, it, now) })
                Result(album.name)
            } catch (e: Exception) {
                Result(album.name, Failure.FAILED)
            }
            onDone(result)
        }
    }

    fun createAlbum(rawName: String, uids: List<Long>, onDone: (Result) -> Unit) {
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
                    })
                    Result(name)
                }
            } catch (e: Exception) {
                Result(name, Failure.FAILED)
            }
            onDone(result)
        }
    }

    private companion object {
        const val MAX_NAME = 60
        val executor = Executors.newSingleThreadExecutor()
    }
}
