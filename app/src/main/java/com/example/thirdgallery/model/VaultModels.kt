package com.example.thirdgallery.model

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.thirdgallery.database.VaultItemEntity

/** The setup / unlock screens of the vault; each one is a panel of VaultSetupActivity. */
enum class VaultStep { INTRO, PIN, RECOVERY, FINGERPRINT, FORGOT }

/** What the PIN pad is asking for: a new vault, unlocking, changing the PIN, or a new PIN after "Forgot PIN". */
enum class VaultPinMode { CREATE, CONFIRM, UNLOCK, CHANGE_OLD, CHANGE_NEW, CHANGE_CONFIRM, RESET_CREATE, RESET_CONFIRM }

/** Why the PIN row shows an error: the two entries differed, or the PIN was wrong ([attempts] in a row). */
sealed class VaultPinError {
    object Mismatch : VaultPinError()
    data class Wrong(val attempts: Int) : VaultPinError()
    object NotCurrent : VaultPinError()
}

/** The PIN pad: how many of the 4 digits are entered and what to show with them. */
data class VaultPinUi(
    val mode: VaultPinMode,
    val filled: Int,
    val error: VaultPinError?,
    val fingerprintKey: Boolean,
)

/** Which part of the vault a folder screen shows: the three built-in views or a folder the user made. */
object VaultFolderKey {
    const val ALL = "all"
    const val PHOTOS = "photos"
    const val VIDEOS = "videos"

    fun user(id: Long): String = "f$id"

    /** The folder id of a user folder key, null for the built-in views. */
    fun userId(key: String): Long? = if (key.startsWith("f")) key.substring(1).toLongOrNull() else null
}

/** One folder card on the vault home: the built-in ones (All items, Photos, Videos) and the user's folders. [coverFile] names the newest item's file in the vault folder. */
data class VaultFolderUi(
    val key: String,
    @StringRes val nameRes: Int,
    /** Name of a user folder; null for the built-in views, which use [nameRes]. */
    val userName: String?,
    @DrawableRes val icon: Int,
    val count: Int,
    val coverFile: String?,
) {
    fun label(context: Context): String = userName ?: context.getString(nameRes)
}

/** Everything the vault home shows. [recent] holds the newest items, newest first; [ids] all item ids in that order. */
data class VaultSummary(
    val total: Int,
    val photos: Int,
    val videos: Int,
    val sizeBytes: Long,
    val folders: List<VaultFolderUi>,
    val recent: List<VaultItemEntity>,
    val ids: List<Long>,
)
