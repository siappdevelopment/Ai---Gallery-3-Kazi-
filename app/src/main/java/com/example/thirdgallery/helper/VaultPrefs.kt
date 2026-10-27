package com.example.thirdgallery.helper

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the Photo vault remembers: a salted hash of the 4-digit PIN, the recovery question (its index and
 * a hash of the answer), whether fingerprint unlock is on, and the uids of the items kept in the vault
 * (newest first). Kept in its own file so it never mixes with the onboarding preferences.
 */
object VaultPrefs {

    private const val FILE = "vault_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_QUESTION = "recovery_question"
    private const val KEY_ANSWER_HASH = "recovery_answer_hash"
    private const val KEY_ANSWER_SALT = "recovery_answer_salt"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_ITEMS = "items"

    fun hasPin(context: Context): Boolean = prefs(context)?.contains(KEY_PIN_HASH) == true

    /** Written synchronously so a killed app never keeps half of a new PIN. */
    fun savePin(context: Context, pin: String) {
        val salt = newSalt()
        prefs(context)?.edit()
            ?.putString(KEY_PIN_SALT, salt)
            ?.putString(KEY_PIN_HASH, hash(salt, pin))
            ?.commit()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val p = prefs(context) ?: return false
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), stored.toByteArray())
    }

    /** [answer] is compared without case, so it is stored lower-cased and trimmed. */
    fun saveRecovery(context: Context, question: Int, answer: String) {
        val salt = newSalt()
        prefs(context)?.edit()
            ?.putInt(KEY_QUESTION, question)
            ?.putString(KEY_ANSWER_SALT, salt)
            ?.putString(KEY_ANSWER_HASH, hash(salt, answer.trim().lowercase()))
            ?.commit()
    }

    fun hasRecovery(context: Context): Boolean = prefs(context)?.contains(KEY_ANSWER_HASH) == true

    fun isFingerprintEnabled(context: Context): Boolean = prefs(context)?.getBoolean(KEY_FINGERPRINT, false) == true

    fun setFingerprintEnabled(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_FINGERPRINT, enabled)?.commit()
    }

    /** Uids (see MediaTypes.uid) of the vault items, newest first. */
    fun itemUids(context: Context): List<Long> =
        prefs(context)?.getString(KEY_ITEMS, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()

    private fun prefs(context: Context) = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun hash(salt: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
