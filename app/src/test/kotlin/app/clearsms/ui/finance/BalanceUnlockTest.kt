package app.clearsms.ui.finance

import androidx.biometric.BiometricManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The capability → action mapping for the device-lock gate: prompt when
 * possible, explain when no screen lock exists, and stay masked (honestly)
 * when authentication is unavailable.
 */
class BalanceUnlockTest {
    @Test
    fun `success prompts`() {
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_SUCCESS))
            .isEqualTo(UnlockDecision.PROMPT)
    }

    @Test
    fun `unknown status still attempts the prompt`() {
        // Older APIs cannot always report device-credential capability; an
        // honest attempt that errors simply leaves balances masked.
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
            .isEqualTo(UnlockDecision.PROMPT)
    }

    @Test
    fun `nothing enrolled means no device lock at all`() {
        // With DEVICE_CREDENTIAL in the allowed set, NONE_ENROLLED can only
        // mean the user has no screen lock of any kind - the UI must explain
        // and offer security settings, never silently reveal.
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
            .isEqualTo(UnlockDecision.NO_DEVICE_LOCK)
    }

    @Test
    fun `hardware problems keep balances masked`() {
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
            .isEqualTo(UnlockDecision.UNAVAILABLE)
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
            .isEqualTo(UnlockDecision.UNAVAILABLE)
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
            .isEqualTo(UnlockDecision.UNAVAILABLE)
        assertThat(BalanceUnlock.decide(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
            .isEqualTo(UnlockDecision.UNAVAILABLE)
    }

    @Test
    fun `device credential is an accepted authenticator, not biometric-only`() {
        // The user asked for "device lock": PIN/pattern/password MUST work.
        assertThat(BalanceUnlock.AUTHENTICATORS and BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .isEqualTo(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        assertThat(BalanceUnlock.AUTHENTICATORS and BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .isEqualTo(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    }
}
