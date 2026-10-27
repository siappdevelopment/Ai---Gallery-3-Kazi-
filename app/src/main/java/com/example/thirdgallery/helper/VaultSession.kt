package com.example.thirdgallery.helper

/**
 * Whether the vault is open right now. Held in memory only, so it is locked again whenever the process
 * dies; the vault screen also locks it as soon as it leaves the screen ("Immediately" auto-lock).
 */
object VaultSession {

    @Volatile
    var isUnlocked: Boolean = false
        private set

    fun unlock() {
        isUnlocked = true
    }

    fun lock() {
        isUnlocked = false
    }
}
