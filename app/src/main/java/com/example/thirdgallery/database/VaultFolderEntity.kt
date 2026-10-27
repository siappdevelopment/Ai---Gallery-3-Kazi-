package com.example.thirdgallery.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A folder inside the Photo vault. "All items", "Photos" and "Videos" are views, not rows. */
@Entity(tableName = "vault_folders")
data class VaultFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** One wrong PIN entered on the vault ([attempt] counts the wrong tries in a row). */
@Entity(tableName = "vault_alerts")
data class VaultAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptedAt: Long,
    val attempt: Int,
)
