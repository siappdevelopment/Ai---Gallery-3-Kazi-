package com.example.thirdgallery.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.example.thirdgallery.R
import com.example.thirdgallery.helper.VaultSession
import com.example.thirdgallery.model.VaultPinError
import com.example.thirdgallery.model.VaultPinMode
import com.example.thirdgallery.model.VaultPinUi
import com.example.thirdgallery.model.VaultStep
import com.example.thirdgallery.repo.VaultRepository
import com.example.thirdgallery.utils.Event

/**
 * The vault's setup flow (intro -> create PIN -> confirm PIN -> recovery question -> fingerprint) and its
 * unlock screen (PIN or fingerprint). A vault that already has a PIN opens straight on the unlock screen.
 * The screen being shown and the recovery choice survive process death; the digits typed never do.
 */
class VaultSetupViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)
    private val handler = Handler(Looper.getMainLooper())

    private val entered = StringBuilder()
    private var firstPin: String? = null
    private var failedAttempts = 0
    private var error: VaultPinError? = null
    private var checking = false
    private var mode: VaultPinMode = pinMode()
        set(value) {
            field = value
            state[KEY_PIN_MODE] = value.ordinal
        }

    private val _step = MutableLiveData(restoreStep())
    val step: LiveData<VaultStep> = _step

    private val _pin = MutableLiveData<VaultPinUi>()
    val pin: LiveData<VaultPinUi> = _pin

    private val _question = MutableLiveData(state.get<Int>(KEY_QUESTION) ?: 0)
    val question: LiveData<Int> = _question

    /** What the user typed as the recovery answer; kept so it survives a recreated activity. */
    var answer: String
        get() = state.get<String>(KEY_ANSWER).orEmpty()
        set(value) {
            state[KEY_ANSWER] = value
        }

    private val _message = MutableLiveData<Event<Int>>()
    val message: LiveData<Event<Int>> = _message

    private val _showFingerprintPrompt = MutableLiveData<Event<Unit>>()
    val showFingerprintPrompt: LiveData<Event<Unit>> = _showFingerprintPrompt

    private val _openVault = MutableLiveData<Event<Unit>>()
    val openVault: LiveData<Event<Unit>> = _openVault

    private val _finish = MutableLiveData<Event<Unit>>()
    val finish: LiveData<Event<Unit>> = _finish

    /** Set up from "Move to vault": the screen ends with RESULT_OK instead of opening the vault. */
    private val returnToCaller: Boolean = state.get<Boolean>(ARG_RETURN) == true

    private val _setupDone = MutableLiveData<Event<Unit>>()
    val setupDone: LiveData<Event<Unit>> = _setupDone

    /** True while the fingerprint prompt is for turning fingerprint unlock on (setup), not for unlocking. */
    var enrollingFingerprint = false
        private set

    init {
        publishPin()
    }

    private fun restoreStep(): VaultStep {
        state.get<Int>(KEY_STEP)?.let { return VaultStep.entries.getOrElse(it) { VaultStep.INTRO } }
        return if (repo.isSetUp) VaultStep.PIN else VaultStep.INTRO
    }

    private fun pinMode(): VaultPinMode {
        // The first PIN is only kept in memory, so a restored "confirm" step starts again from "create".
        val saved = state.get<Int>(KEY_PIN_MODE)?.let { VaultPinMode.entries.getOrNull(it) }
        return when {
            saved == VaultPinMode.CONFIRM && firstPin == null -> VaultPinMode.CREATE
            saved != null -> saved
            repo.isSetUp -> VaultPinMode.UNLOCK
            else -> VaultPinMode.CREATE
        }
    }

    private fun setStep(step: VaultStep) {
        state[KEY_STEP] = step.ordinal
        _step.value = step
    }

    private fun publishPin() {
        _pin.value = VaultPinUi(
            mode = mode,
            filled = entered.length,
            error = error,
            fingerprintKey = mode == VaultPinMode.UNLOCK && repo.isFingerprintEnabled,
        )
    }

    /** The intro's "Set up vault". */
    fun startSetup() {
        reset(VaultPinMode.CREATE)
        setStep(VaultStep.PIN)
    }

    fun onDigit(digit: Int) {
        if (checking || entered.length >= PIN_LENGTH) return
        entered.append(digit)
        error = null
        publishPin()
        if (entered.length == PIN_LENGTH) {
            checking = true
            // Lets the fourth dot show before the screen moves on.
            handler.postDelayed({ checkPin() }, CHECK_DELAY_MS)
        }
    }

    fun onDelete() {
        if (checking || entered.isEmpty()) return
        entered.deleteCharAt(entered.length - 1)
        publishPin()
    }

    private fun checkPin() {
        checking = false
        val value = entered.toString()
        entered.clear()
        when (mode) {
            VaultPinMode.CREATE -> {
                firstPin = value
                mode = VaultPinMode.CONFIRM
            }
            VaultPinMode.CONFIRM -> {
                if (value == firstPin) {
                    repo.createPin(value)
                    firstPin = null
                    mode = VaultPinMode.UNLOCK
                    setStep(VaultStep.RECOVERY)
                } else {
                    firstPin = null
                    mode = VaultPinMode.CREATE
                    error = VaultPinError.Mismatch
                }
            }
            VaultPinMode.UNLOCK -> {
                if (repo.verifyPin(value)) {
                    failedAttempts = 0
                    unlocked()
                    return
                }
                failedAttempts++
                error = VaultPinError.Wrong(failedAttempts)
            }
        }
        publishPin()
    }

    /** The PIN pad's fingerprint key. */
    fun onFingerprintKey() {
        if (!repo.isFingerprintEnabled) return
        enrollingFingerprint = false
        _showFingerprintPrompt.value = Event(Unit)
    }

    /** The recovery step: pick one of the questions. */
    fun selectQuestion(index: Int) {
        state[KEY_QUESTION] = index
        _question.value = index
    }

    fun saveRecovery() {
        val text = answer.trim()
        if (text.length < MIN_ANSWER) {
            _message.value = Event(R.string.vault_recovery_enter_answer)
            return
        }
        repo.saveRecovery(_question.value ?: 0, text)
        setStep(VaultStep.FINGERPRINT)
    }

    fun skipRecovery() = setStep(VaultStep.FINGERPRINT)

    /** The fingerprint step's "Use fingerprint": asks for the fingerprint once before turning it on. */
    fun useFingerprint() {
        val message = fingerprintProblem()
        if (message != null) {
            _message.value = Event(message)
            return
        }
        enrollingFingerprint = true
        _showFingerprintPrompt.value = Event(Unit)
    }

    /** The fingerprint prompt was passed. */
    fun onFingerprintSuccess() {
        if (enrollingFingerprint) {
            enrollingFingerprint = false
            repo.setFingerprintEnabled(true)
            completeSetup()
        } else {
            unlocked()
        }
    }

    /** The fingerprint step's "Not now". */
    fun skipFingerprint() = completeSetup()

    @StringRes
    private fun fingerprintProblem(): Int? =
        when (BiometricManager.from(getApplication()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.vault_fp_none_enrolled
            else -> R.string.vault_fp_unavailable
        }

    private fun completeSetup() {
        _message.value = Event(R.string.vault_ready)
        // Moving an item in does not open the vault, so it stays locked and asks for the PIN on the next visit.
        if (returnToCaller) _setupDone.value = Event(Unit) else unlocked()
    }

    private fun unlocked() {
        VaultSession.unlock()
        _openVault.value = Event(Unit)
    }

    /** The back button or system back; what it does depends on the screen. */
    fun onBack() {
        when (_step.value) {
            VaultStep.PIN -> when (mode) {
                VaultPinMode.CONFIRM -> reset(VaultPinMode.CREATE)
                VaultPinMode.CREATE -> {
                    reset(VaultPinMode.CREATE)
                    setStep(VaultStep.INTRO)
                }
                else -> _finish.value = Event(Unit)
            }
            // The PIN is already saved from here on, so leaving ends the setup; the next visit unlocks.
            else -> _finish.value = Event(Unit)
        }
    }

    private fun reset(next: VaultPinMode) {
        handler.removeCallbacksAndMessages(null)
        checking = false
        entered.clear()
        firstPin = null
        error = null
        mode = next
        publishPin()
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    companion object {
        const val ARG_RETURN = "return_to_caller"
        private const val KEY_STEP = "vault_step"
        private const val KEY_PIN_MODE = "vault_pin_mode"
        private const val KEY_QUESTION = "vault_question"
        private const val KEY_ANSWER = "vault_answer"
        private const val PIN_LENGTH = 4
        private const val MIN_ANSWER = 2
        private const val CHECK_DELAY_MS = 200L
    }
}
