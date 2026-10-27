package com.example.thirdgallery.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VaultDao {

    @Insert
    fun insert(item: VaultItemEntity): Long

    /** The vault's items, newest first; the screens follow this live. */
    @Query("SELECT * FROM vault_items WHERE committed = 1 ORDER BY addedAt DESC, id DESC")
    fun observeItems(): LiveData<List<VaultItemEntity>>

    @Query("SELECT COUNT(*) FROM vault_items WHERE committed = 1")
    fun observeCount(): LiveData<Int>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    fun byId(id: Long): VaultItemEntity?

    @Query("SELECT * FROM vault_items WHERE id IN (:ids) AND committed = 1")
    fun byIds(ids: List<Long>): List<VaultItemEntity>

    @Query("SELECT * FROM vault_items WHERE committed = 0")
    fun pending(): List<VaultItemEntity>

    @Query("SELECT * FROM vault_items WHERE committed = 1")
    fun committed(): List<VaultItemEntity>

    @Query("UPDATE vault_items SET committed = 1 WHERE id = :id")
    fun markCommitted(id: Long)

    @Query("UPDATE vault_items SET folderId = :folderId WHERE id IN (:ids)")
    fun setFolder(ids: List<Long>, folderId: Long)

    @Query("DELETE FROM vault_items WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM vault_items WHERE id IN (:ids)")
    fun deleteAll(ids: List<Long>)

    // folders

    @Query("SELECT * FROM vault_folders ORDER BY createdAt ASC, id ASC")
    fun observeFolders(): LiveData<List<VaultFolderEntity>>

    @Query("SELECT * FROM vault_folders ORDER BY createdAt ASC, id ASC")
    fun folders(): List<VaultFolderEntity>

    @Insert
    fun insertFolder(folder: VaultFolderEntity): Long

    @Query("DELETE FROM vault_folders")
    fun clearFolders()

    // break-in alerts

    @Insert
    fun insertAlert(alert: VaultAlertEntity)

    @Query("SELECT * FROM vault_alerts ORDER BY attemptedAt DESC, id DESC")
    fun observeAlerts(): LiveData<List<VaultAlertEntity>>

    @Query("DELETE FROM vault_alerts")
    fun clearAlerts()
}
