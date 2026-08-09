package app.clearsms.ui.finance

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** What the eye tap should do, derived from `BiometricManager.canAuthenticate`. */
enum class UnlockDecision {
    /** Show the system prompt (biometric or device credential). */
    PROMPT,

    /**
     * No screen lock is configured at all - the user cannot authenticate.
     * The UI explains that a screen lock is required and offers the system
     * security settings; it never silently reveals.
     */
    NO_DEVICE_LOCK,

    /** Authentication is (temporarily) unavailable; balances stay hidden. */
    UNAVAILABLE,
}

/**
 * Device-lock gate for revealing masked balances.
 *
 * [AUTHENTICATORS] deliberately allows BIOMETRIC_WEAK *or* DEVICE_CREDENTIAL:
 * the feature is "unlock with your device lock", so fingerprint, face, PIN,
 * pattern and password are all accepted - never biometric-only.
 */
object BalanceUnlock {
    const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Maps a `canAuthenticate(AUTHENTICATORS)` status to a decision:
     * - SUCCESS → prompt.
     * - STATUS_UNKNOWN → prompt anyway (older APIs cannot always tell; an
     *   honest attempt that errors out simply leaves balances masked).
     * - NONE_ENROLLED → with DEVICE_CREDENTIAL allowed this means no screen
     *   lock of any kind exists, so explain instead of dead-ending.
     * - everything else (no hardware, hardware busy, security update
     *   required, unsupported combination) → unavailable, stay masked.
     */
    fun decide(canAuthenticate: Int): UnlockDecision =
        when (canAuthenticate) {
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            -> UnlockDecision.PROMPT
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> UnlockDecision.NO_DEVICE_LOCK
            else -> UnlockDecision.UNAVAILABLE
        }

    /**
     * Shows the system authentication sheet. [onResult] receives true only
     * on success; errors, lockouts and user cancellation all report false
     * (the caller keeps balances masked). A single rejected fingerprint
     * (`onAuthenticationFailed`) is not terminal - the sheet stays up and
     * the user may retry or fall back to their PIN/pattern/password.
     */
    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    ) {
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    onResult(false)
                }
            }
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info)
    }
}
