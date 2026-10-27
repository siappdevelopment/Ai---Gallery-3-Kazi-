package com.example.thirdgallery.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.thirdgallery.database.VaultItemEntity

/** The setup / unlock screens of the vault; each one is a panel of VaultSetupActivity. */
enum class VaultStep { INTRO, PIN, RECOVERY, FINGERPRINT }

enum class VaultPinMode { CREATE, CONFIRM, UNLOCK }

/** Why the PIN row shows an error: the two entries differed, or the PIN was wrong ([attempts] in a row). */
sealed class VaultPinError {
    object Mismatch : VaultPinError()
    data class Wrong(val attempts: Int) : VaultPinError()
}

/** The PIN pad: how many of the 4 digits are entered and what to show with them. */
data class VaultPinUi(
    val mode: VaultPinMode,
    val filled: Int,
    val error: VaultPinError?,
    val fingerprintKey: Boolean,
)

/** One folder card on the vault home: the built-in ones (All items, Photos, Videos) and the default ones. [coverFile] names the newest item's file in the vault folder. */
data class VaultFolderUi(
    val key: String,
    @StringRes val name: Int,
    @DrawableRes val icon: Int,
    val count: Int,
    val coverFile: String?,
)

/** Everything the vault home shows. [recent] holds the newest items, newest first. */
data class VaultSummary(
    val total: Int,
    val photos: Int,
    val videos: Int,
    val sizeBytes: Long,
    val folders: List<VaultFolderUi>,
    val recent: List<VaultItemEntity>,
)
