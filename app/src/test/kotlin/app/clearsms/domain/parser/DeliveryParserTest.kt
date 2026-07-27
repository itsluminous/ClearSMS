package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class DeliveryParserTest {
    private val parser = DeliveryParser()

    /** The date of the SMS — relative phrases must resolve against THIS, not the clock. */
    private val messageDate = LocalDate.of(2026, 7, 20)

    @Test
    fun `arriving tomorrow resolves against the message date`() {
        val result = parser.parse("AX-EKARTL", "Your Flipkart order OD334455667788 is arriving tomorrow. Track: https://ekart.example")
        assertThat(result).isNotNull()
        assertThat(result!!.relativeDays).isEqualTo(1L)
        assertThat(result.expectedDate(messageDate)).isEqualTo(LocalDate.of(2026, 7, 21))
        assertThat(result.reference).isEqualTo("OD334455667788")
    }

    @Test
    fun `out for delivery resolves to the message date itself`() {
        val result = parser.parse("DLHVRY", "Your package 1234567890 is out for delivery and will reach you by 8 PM.")
        assertThat(result).isNotNull()
        assertThat(result!!.expectedDate(messageDate)).isEqualTo(messageDate)
        assertThat(result.merchant).isEqualTo("Delhivery")
    }

    @Test
    fun `explicit delivery date wins over relative phrasing`() {
        val result = parser.parse("AMAZIN", "Your Amazon order 403-1234567-1234567 will be delivered on 25-07-2026.")
        assertThat(result).isNotNull()
        assertThat(result!!.explicitDate).isEqualTo(LocalDate.of(2026, 7, 25))
        assertThat(result.expectedDate(messageDate)).isEqualTo(LocalDate.of(2026, 7, 25))
        assertThat(result.merchant).isEqualTo("Amazon")
    }

    @Test
    fun `expected delivery by date is parsed`() {
        val result = parser.parse("BLUDRT", "Shipment AWB 98765432101 booked. Expected delivery by 28-Jul-26.")
        assertThat(result).isNotNull()
        assertThat(result!!.explicitDate).isEqualTo(LocalDate.of(2026, 7, 28))
        assertThat(result.merchant).isEqualTo("Blue Dart")
    }

    @Test
    fun `message without any delivery expectation is ignored`() {
        assertThat(parser.parse("AMAZIN", "Your Amazon order 403-1234567 has been shipped.")).isNull()
    }

    @Test
    fun `delivered confirmation without expectation is ignored`() {
        assertThat(parser.parse("EKARTL", "Your order OD1122334455 was handed to the courier.")).isNull()
    }
}
