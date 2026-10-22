package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AlbumDao {

    @Query(
        "SELECT a.id AS id, a.name AS name, COUNT(m.uid) AS total, " +
            "(SELECT x.mediaUid FROM album_items x INNER JOIN media mm ON mm.uid = x.mediaUid WHERE x.albumId = a.id ORDER BY x.addedAt DESC, x.mediaUid DESC LIMIT 1) AS cover " +
            "FROM albums a LEFT JOIN album_items i ON i.albumId = a.id LEFT JOIN media m ON m.uid = i.mediaUid GROUP BY a.id ORDER BY a.createdAt DESC, a.id DESC"
    )
    fun observeAlbums(): LiveData<List<UserAlbumRow>>

    /** App albums for the Albums tab; only items that still exist on the device are counted. */
    @Query(
        "SELECT a.id AS id, a.name AS name, COUNT(m.uid) AS total, COALESCE(MAX(i.addedAt), a.createdAt) AS lastDate, " +
            "(SELECT x.mediaUid FROM album_items x INNER JOIN media mm ON mm.uid = x.mediaUid WHERE x.albumId = a.id ORDER BY x.addedAt DESC, x.mediaUid DESC LIMIT 1) AS cover0, " +
            "(SELECT x.mediaUid FROM album_items x INNER JOIN media mm ON mm.uid = x.mediaUid WHERE x.albumId = a.id ORDER BY x.addedAt DESC, x.mediaUid DESC LIMIT 1 OFFSET 1) AS cover1, " +
            "(SELECT x.mediaUid FROM album_items x INNER JOIN media mm ON mm.uid = x.mediaUid WHERE x.albumId = a.id ORDER BY x.addedAt DESC, x.mediaUid DESC LIMIT 1 OFFSET 2) AS cover2, " +
            "(SELECT x.mediaUid FROM album_items x INNER JOIN media mm ON mm.uid = x.mediaUid WHERE x.albumId = a.id ORDER BY x.addedAt DESC, x.mediaUid DESC LIMIT 1 OFFSET 3) AS cover3 " +
            "FROM albums a LEFT JOIN album_items i ON i.albumId = a.id LEFT JOIN media m ON m.uid = i.mediaUid GROUP BY a.id"
    )
    fun observeAlbumCards(): LiveData<List<AlbumCardRow>>

    @Query("SELECT COUNT(*) FROM albums WHERE name = :name COLLATE NOCASE")
    fun countByName(name: String): Int

    @Insert
    fun insertAlbum(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertItems(items: List<AlbumItemEntity>)
}
