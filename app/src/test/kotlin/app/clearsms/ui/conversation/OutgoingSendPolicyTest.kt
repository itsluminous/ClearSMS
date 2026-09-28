package app.clearsms.ui.conversation

import app.clearsms.R
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.ui.conversation.MessageDetails.Transport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pure rule behind the bug where an MMS read "Sent" for two and a half
 * minutes while the platform was still trying: from (transport, persisted
 * status, has-a-result-yet) to what the row - and so the bubble - may
 * claim once the silent result window has elapsed.
 */
class OutgoingSendPolicyTest {
    @Test
    fun `an in-flight MMS reads Sending after a silent window, never Sent`() {
        val after = OutgoingSendPolicy.afterSilentWindow(Transport.MMS, DeliveryStatus.SENDING, resultRecorded = false)

        assertThat(after).isEqualTo(DeliveryStatus.SENDING)
        assertThat(after).isNotEqualTo(DeliveryStatus.SENT)
        assertThat(deliveryStatusLabelRes(after)).isEqualTo(R.string.conversation_sending)
    }

    @Test
    fun `an in-flight SMS is closed as Sent by a silent window`() {
        val after = OutgoingSendPolicy.afterSilentWindow(Transport.SMS, DeliveryStatus.SENDING, resultRecorded = false)

        assertThat(after).isEqualTo(DeliveryStatus.SENT)
        assertThat(deliveryStatusLabelRes(after)).isEqualTo(R.string.conversation_sent)
    }

    @Test
    fun `a recorded result is final for either transport`() {
        for (transport in Transport.entries) {
            for (status in listOf(DeliveryStatus.SENT, DeliveryStatus.FAILED, DeliveryStatus.DELIVERED)) {
                assertThat(OutgoingSendPolicy.afterSilentWindow(transport, status, resultRecorded = true))
                    .isEqualTo(status)
            }
        }
        // The failure path lands on Not sent whatever the transport.
        assertThat(deliveryStatusLabelRes(OutgoingSendPolicy.afterSilentWindow(Transport.MMS, DeliveryStatus.FAILED, true)))
            .isEqualTo(R.string.conversation_not_sent)
    }

    @Test
    fun `no persisted status other than SENDING is touched by the window`() {
        for (transport in Transport.entries) {
            for (status in DeliveryStatus.entries.filter { it != DeliveryStatus.SENDING }) {
                assertThat(OutgoingSendPolicy.afterSilentWindow(transport, status, resultRecorded = false))
                    .isEqualTo(status)
            }
        }
    }
}
