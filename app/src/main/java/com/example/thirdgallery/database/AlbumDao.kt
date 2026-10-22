package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AlbumDao {

    @Query(
        "SELECT a.id AS id, a.name AS name, COUNT(i.mediaUid) AS total, " +
            "(SELECT x.mediaUid FROM album_items x WHERE x.albumId = a.id ORDER BY x.addedAt DESC, x.mediaUid DESC LIMIT 1) AS cover " +
            "FROM albums a LEFT JOIN album_items i ON i.albumId = a.id GROUP BY a.id ORDER BY a.createdAt DESC, a.id DESC"
    )
    fun observeAlbums(): LiveData<List<UserAlbumRow>>

    @Query("SELECT COUNT(*) FROM albums WHERE name = :name COLLATE NOCASE")
    fun countByName(name: String): Int

    @Insert
    fun insertAlbum(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<AlbumItemEntity>)
}
