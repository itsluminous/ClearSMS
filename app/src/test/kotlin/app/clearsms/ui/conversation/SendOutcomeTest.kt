package app.clearsms.ui.conversation

import android.provider.Telephony
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The send-outcome machine: SENDING until the radio result window closes,
 * FAILED the moment a failure is recorded against the provider row, SENT
 * when the window passes without one.
 */
class SendOutcomeTest {
    @Test
    fun `unresolved send inside the window stays sending`() {
        val status =
            SendOutcome.resolve(
                providerType = Telephony.Sms.MESSAGE_TYPE_SENT,
                elapsedMs = 1_000,
            )
        assertThat(status).isEqualTo(SendStatus.SENDING)
    }

    @Test
    fun `sending resolves to sent once the window closes without a failure`() {
        val status =
            SendOutcome.resolve(
                providerType = Telephony.Sms.MESSAGE_TYPE_SENT,
                elapsedMs = SendOutcome.RESULT_WINDOW_MS,
            )
        assertThat(status).isEqualTo(SendStatus.SENT)
    }

    @Test
    fun `sending resolves to failed as soon as a failure is recorded`() {
        val status =
            SendOutcome.resolve(
                providerType = Telephony.Sms.MESSAGE_TYPE_FAILED,
                elapsedMs = 500,
            )
        assertThat(status).isEqualTo(SendStatus.FAILED)
    }

    @Test
    fun `a recorded failure wins even after the window has closed`() {
        val status =
            SendOutcome.resolve(
                providerType = Telephony.Sms.MESSAGE_TYPE_FAILED,
                elapsedMs = SendOutcome.RESULT_WINDOW_MS + 1,
            )
        assertThat(status).isEqualTo(SendStatus.FAILED)
    }

    @Test
    fun `unobservable provider row stays sending inside the window`() {
        assertThat(SendOutcome.resolve(providerType = null, elapsedMs = 0))
            .isEqualTo(SendStatus.SENDING)
    }

    @Test
    fun `unobservable provider row resolves to sent after the window`() {
        assertThat(SendOutcome.resolve(providerType = null, elapsedMs = SendOutcome.RESULT_WINDOW_MS))
            .isEqualTo(SendStatus.SENT)
    }

    @Test
    fun `retry restarts the machine at sending`() {
        // A failed attempt...
        val failed = SendOutcome.resolve(Telephony.Sms.MESSAGE_TYPE_FAILED, 2_000)
        assertThat(failed).isEqualTo(SendStatus.FAILED)
        // ...re-dispatched writes a fresh provider row and resets the clock.
        val retried = SendOutcome.resolve(Telephony.Sms.MESSAGE_TYPE_SENT, 0)
        assertThat(retried).isEqualTo(SendStatus.SENDING)
    }
}
