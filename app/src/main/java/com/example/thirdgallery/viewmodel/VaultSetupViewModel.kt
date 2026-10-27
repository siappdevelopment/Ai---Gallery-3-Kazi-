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
import java.util.concurrent.Executors

/**
 * The vault's PIN screens: the setup flow (intro -> create PIN -> confirm PIN -> recovery question -> fingerprint),
 * the unlock screen (PIN or fingerprint) with "Forgot PIN?", and the two short flows the settings start (change
 * the PIN, edit the recovery question). A vault that already has a PIN opens straight on the unlock screen.
 * The screen being shown and the recovery choice survive process death; the digits typed never do.
 */
class VaultSetupViewModel(
    application: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)
    private val handler = Handler(Looper.getMainLooper())

    /** Which flow this screen was opened for. */
    private val flow: Int = state.get<Int>(ARG_FLOW) ?: FLOW_DEFAULT

    private val entered = StringBuilder()
    private var firstPin: String? = null
    private var failedAttempts = 0
    private var error: VaultPinError? = null
    private var checking = false
    private var mode: VaultPinMode = initialMode()
        set(value) {
            field = value
            state[KEY_PIN_MODE] = value.ordinal
        }

    private val _step = MutableLiveData(initialStep())
    val step: LiveData<VaultStep> = _step

    private val _pin = MutableLiveData<VaultPinUi>()
    val pin: LiveData<VaultPinUi> = _pin

    private val _question = MutableLiveData(state.get<Int>(KEY_QUESTION) ?: repo.recoveryQuestion.coerceAtLeast(0))
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

    /** Set up from "Move to vault" (or a finished settings flow): the screen ends with RESULT_OK instead of opening the vault. */
    private val returnToCaller: Boolean = state.get<Boolean>(ARG_RETURN) == true

    private val _setupDone = MutableLiveData<Event<Unit>>()
    val setupDone: LiveData<Event<Unit>> = _setupDone

    private val _forgotError = MutableLiveData<Event<Unit>>()

    /** The answer typed on the Forgot PIN screen did not match. */
    val forgotError: LiveData<Event<Unit>> = _forgotError

    /** True while the fingerprint prompt is for turning fingerprint unlock on (setup), not for unlocking. */
    var enrollingFingerprint = false
        private set

    /** The recovery screen is editing an existing question (from the settings), not part of the setup. */
    val isRecoveryEdit: Boolean get() = flow == FLOW_RECOVERY

    /** The vault has a recovery question, so Forgot PIN can ask it. */
    val hasRecovery: Boolean get() = repo.hasRecovery

    /** Resource of the recovery question to ask on the Forgot PIN screen. */
    val recoveryQuestionIndex: Int get() = repo.recoveryQuestion

    init {
        publishPin()
    }

    private fun initialStep(): VaultStep {
        state.get<Int>(KEY_STEP)?.let { return VaultStep.entries.getOrElse(it) { VaultStep.INTRO } }
        return when {
            flow == FLOW_RECOVERY -> VaultStep.RECOVERY
            repo.isSetUp -> VaultStep.PIN
            else -> VaultStep.INTRO
        }
    }

    private fun initialMode(): VaultPinMode {
        // The first PIN is only kept in memory, so a restored "confirm" step starts again from its first entry.
        val saved = state.get<Int>(KEY_PIN_MODE)?.let { VaultPinMode.entries.getOrNull(it) }
        return when {
            saved == VaultPinMode.CONFIRM -> VaultPinMode.CREATE
            saved == VaultPinMode.CHANGE_CONFIRM -> VaultPinMode.CHANGE_NEW
            saved == VaultPinMode.RESET_CONFIRM -> VaultPinMode.RESET_CREATE
            saved != null -> saved
            flow == FLOW_CHANGE_PIN -> VaultPinMode.CHANGE_OLD
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
                    seedFolders()
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
                saveAlert(failedAttempts)
            }
            VaultPinMode.CHANGE_OLD -> {
                if (repo.verifyPin(value)) mode = VaultPinMode.CHANGE_NEW else error = VaultPinError.NotCurrent
            }
            VaultPinMode.CHANGE_NEW -> {
                firstPin = value
                mode = VaultPinMode.CHANGE_CONFIRM
            }
            VaultPinMode.CHANGE_CONFIRM -> {
                if (value == firstPin) {
                    repo.createPin(value)
                    _message.value = Event(R.string.vault_pin_changed)
                    _setupDone.value = Event(Unit)
                    return
                }
                firstPin = null
                mode = VaultPinMode.CHANGE_NEW
                error = VaultPinError.Mismatch
            }
            VaultPinMode.RESET_CREATE -> {
                firstPin = value
                mode = VaultPinMode.RESET_CONFIRM
            }
            VaultPinMode.RESET_CONFIRM -> {
                if (value == firstPin) {
                    repo.createPin(value)
                    seedFolders()
                    failedAttempts = 0
                    _message.value = Event(R.string.vault_new_pin_set)
                    unlocked()
                    return
                }
                firstPin = null
                mode = VaultPinMode.RESET_CREATE
                error = VaultPinError.Mismatch
            }
        }
        publishPin()
    }

    /** A new vault starts with its default folders. */
    private fun seedFolders() {
        worker.execute {
            try {
                repo.ensureDefaultFolders()
            } catch (e: Exception) {
                // Seeded again the next time the vault opens.
            }
        }
    }

    private fun saveAlert(attempt: Int) {
        worker.execute {
            try {
                repo.logWrongPin(attempt)
            } catch (e: Exception) {
                // A missing log entry must never get in the way of unlocking.
            }
        }
    }

    /** The PIN pad's fingerprint key. */
    fun onFingerprintKey() {
        if (!repo.isFingerprintEnabled) return
        enrollingFingerprint = false
        _showFingerprintPrompt.value = Event(Unit)
    }

    /** "Forgot PIN?": the recovery question (or the reset when there is none). */
    fun showForgot() {
        reset(VaultPinMode.UNLOCK)
        setStep(VaultStep.FORGOT)
    }

    /** "Verify answer": a right answer leads to choosing a new PIN. */
    fun verifyAnswer(text: String) {
        if (repo.verifyAnswer(text)) {
            reset(VaultPinMode.RESET_CREATE)
            setStep(VaultStep.PIN)
        } else {
            _forgotError.value = Event(Unit)
        }
    }

    /** The vault was reset from the Forgot PIN screen: nothing is left to unlock. */
    fun onVaultReset() {
        VaultSession.lock()
        _message.value = Event(R.string.vault_reset_done)
        _finish.value = Event(Unit)
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
        if (isRecoveryEdit) {
            _message.value = Event(R.string.vault_recovery_saved)
            _setupDone.value = Event(Unit)
        } else {
            setStep(VaultStep.FINGERPRINT)
        }
    }

    fun skipRecovery() {
        if (isRecoveryEdit) _finish.value = Event(Unit) else setStep(VaultStep.FINGERPRINT)
    }

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
        VaultSession.unlock(getApplication())
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
                VaultPinMode.CHANGE_CONFIRM -> reset(VaultPinMode.CHANGE_NEW)
                VaultPinMode.RESET_CREATE, VaultPinMode.RESET_CONFIRM -> {
                    reset(VaultPinMode.UNLOCK)
                    setStep(VaultStep.FORGOT)
                }
                else -> _finish.value = Event(Unit)
            }
            VaultStep.FORGOT -> {
                reset(VaultPinMode.UNLOCK)
                setStep(VaultStep.PIN)
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
        const val ARG_FLOW = "vault_flow"
        const val FLOW_DEFAULT = 0
        const val FLOW_CHANGE_PIN = 1
        const val FLOW_RECOVERY = 2
        private const val KEY_STEP = "vault_step"
        private const val KEY_PIN_MODE = "vault_pin_mode"
        private const val KEY_QUESTION = "vault_question"
        private const val KEY_ANSWER = "vault_answer"
        private const val PIN_LENGTH = 4
        private const val MIN_ANSWER = 2
        private const val CHECK_DELAY_MS = 200L
        private val worker = Executors.newSingleThreadExecutor()
    }
}
