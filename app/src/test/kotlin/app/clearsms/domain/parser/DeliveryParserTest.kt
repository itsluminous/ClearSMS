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

    // region body-named couriers (P1c-P1f): the sender does not identify the
    // courier, only the text does.

    @Test
    fun `india post article out for delivery resolves courier and article number`() {
        // Defect P1c: "INDIAPOST" (no space) named only in the body.
        val result =
            parser.parse(
                "CP-030421",
                "Article No:JQ023293863IN Out for delivery through JEEVAN S (Beat No:B6) on 27/05/2025 10:35:07 ? INDIAPOST",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("India Post")
        assertThat(result.reference).isEqualTo("JQ023293863IN")
        assertThat(result.expectedDate(messageDate)).isEqualTo(messageDate)
    }

    @Test
    fun `bank sender delivering a card via blue dart shows the courier not the bank`() {
        // Defect P1d: the SENDER is HDFC Bank; Blue Dart is the delivery
        // agent named in the body. The courier is what the Alerts card shows
        // as the agent — the message itself remains the bank's.
        val result =
            parser.parse(
                "VM-HDFCBK",
                "Your HDFC Bank Debit Card will be delivered today via Blue Dart Awb #33888164111. " +
                    "Track here https://hdfcbk.example/HDFCBK/s/edWNM96k",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("Blue Dart")
        assertThat(result.reference).isEqualTo("33888164111")
        assertThat(result.expectedDate(messageDate)).isEqualTo(messageDate)
    }

    @Test
    fun `cheque book dispatch via blue dart carries awb and explicit date`() {
        // Defect P1e: same bank-sender shape with an explicit delivery date.
        val result =
            parser.parse(
                "VM-HDFCBK",
                "Your HDFC Bank Cheque Book is dispatched via Blue Dart Awb #31731645562 & will be delivered by 17-05-2023.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("Blue Dart")
        assertThat(result.reference).isEqualTo("31731645562")
        assertThat(result.explicitDate).isEqualTo(LocalDate.of(2023, 5, 17))
    }

    @Test
    fun `dominos is recognized in both spellings`() {
        // Defect P1f: "Domino's" and "DOMINOS" from generic shortcodes.
        val apostrophe =
            parser.parse("VD-DMTRAK", "Dear guest, your Domino's order is out for delivery. Click on tinyurl.example/yper5jng to track it.")
        assertThat(apostrophe).isNotNull()
        assertThat(apostrophe!!.merchant).isEqualTo("Domino's")
        assertThat(apostrophe.expectedDate(messageDate)).isEqualTo(messageDate)

        val plain =
            parser.parse(
                "VD-DMTRAK",
                "Dear Guest, your DOMINOS order is out for delivery with OMKAR MALLIKARJUN. " +
                    "Click on https://dmn.example.net/PnXa8 to track your order",
            )
        assertThat(plain).isNotNull()
        assertThat(plain!!.merchant).isEqualTo("Domino's")
    }

    @Test
    fun `nimbuspost courier order with underscore reference is resolved`() {
        val result =
            parser.parse(
                "JD-NPSMSA",
                "Hi Prakash Kumar, Your Order OPD_NHK-130 from (Offiga , THE SNAPMO...) is out for delivery. " +
                    "Nimbuspost Courier - https://odrtrk.example/t/oqRNgNwk",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("Nimbuspost")
        assertThat(result.reference).isEqualTo("OPD_NHK-130")
        assertThat(result.expectedDate(messageDate)).isEqualTo(messageDate)
    }

    @Test
    fun `courier named in body without a delivery expectation stays ignored`() {
        // Near-miss: a courier mention alone must not fabricate a delivery.
        assertThat(
            parser.parse("VM-HDFCBK", "Your pickup request via Blue Dart Awb #31731645562 is registered."),
        ).isNull()
    }

    // endregion

    // region brand identification (Croma, INDPOST, URL domains)

    @Test
    fun `croma is recognized from the team signature and its order url`() {
        val result =
            parser.parse(
                "CP-610700",
                "Hi, Your SOA060358391060 is out for delivery! Track it at " +
                    "https://www.croma.com/my-account/orders Rgds, Team Croma",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("Croma")
    }

    @Test
    fun `croma is recognized from the url host alone`() {
        val result =
            parser.parse(
                "CP-610700",
                "Hi, Your order SOA060358391060 is out for delivery! " +
                    "Track: https://www.croma.com/my-account/orders",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("Croma")
    }

    @Test
    fun `indpost sender spelling resolves to india post`() {
        val result =
            parser.parse(
                "INDPOST",
                "Article No:UC440591633IN Out for delivery through BASAVARAJ KA (Beat No:B14) on 29/06/2022 10:53:11 - INDPOST",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("India Post")
        assertThat(result.reference).isEqualTo("UC440591633IN")
    }

    @Test
    fun `a brand name inside an unrelated url never attributes the delivery`() {
        // The tracking link's path mentions another brand; the URL is
        // excluded from name matching and its HOST is not a known brand
        // domain, so no misattribution happens.
        val result =
            parser.parse(
                "XY-COURIER",
                "Your parcel AWB 5523104 is out for delivery. Track at https://trk.example.net/amazon/flipkart-deals",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isNull()
    }

    @Test
    fun `a brand domain embedded in a hostile hostname never matches`() {
        val result =
            parser.parse(
                "XY-COURIER",
                "Your parcel AWB 5523104 is out for delivery. Track at https://croma.com.evil.example/track",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isNull()
    }

    // endregion
}
