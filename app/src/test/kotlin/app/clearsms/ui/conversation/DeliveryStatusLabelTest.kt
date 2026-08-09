package app.clearsms.ui.conversation

import app.clearsms.R
import app.clearsms.data.db.DeliveryStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bubble status line maps each persisted [DeliveryStatus] to its label -
 * and a message with no delivery report (delivery reports off, or the
 * carrier sent none) reads "Sent", never "Delivered".
 */
class DeliveryStatusLabelTest {
    @Test
    fun `each status maps to its own label`() {
        assertThat(deliveryStatusLabelRes(DeliveryStatus.SENDING)).isEqualTo(R.string.conversation_sending)
        assertThat(deliveryStatusLabelRes(DeliveryStatus.SENT)).isEqualTo(R.string.conversation_sent)
        assertThat(deliveryStatusLabelRes(DeliveryStatus.DELIVERED)).isEqualTo(R.string.conversation_delivered)
        assertThat(deliveryStatusLabelRes(DeliveryStatus.FAILED)).isEqualTo(R.string.conversation_not_sent)
    }

    @Test
    fun `no recorded status reads as Sent, never Delivered`() {
        assertThat(deliveryStatusLabelRes(null)).isEqualTo(R.string.conversation_sent)
        assertThat(deliveryStatusLabelRes(null)).isNotEqualTo(R.string.conversation_delivered)
    }
}
