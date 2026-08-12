package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parser-level regressions for the round-M reported defects. All fixture
 * values (names, PNRs, consumer numbers, links) are SYNTHETIC.
 */
class RoundMDefectsParserTest {
    private val parser = TransactionParser()

    // region defect 1: per-unit pitch amount fabricated a debit

    @Test
    fun `rewards pitch with a per-unit amount never parses as a transaction`() {
        val result =
            parser.parse(
                "JD-KOTAKB-P",
                "Still spending without rewards? Link Kotak UPI Rupay CC to your UPI app & " +
                    "earn 3 pts on every Rs 100 spent. Apply: https://1.example.bank.in/KOTAKB/AbCdEf T&C",
            )
        assertThat(result).isNull()
    }

    @Test
    fun `cashback per-rupee pitch never parses as a transaction`() {
        val result =
            parser.parse(
                "JD-OFFERS",
                "Get 5% cashback per Rs 500 paid with your card this weekend. T&C apply.",
            )
        assertThat(result).isNull()
    }

    @Test
    fun `a genuine card spend still parses after the hypothetical-amount scrub`() {
        val result =
            parser.parse(
                "JD-KOTAKB-S",
                "Spent Rs.4,250.00 on Kotak Bank Card x4321 at AMAZON on 10-08-26. Avl Lmt Rs 55,000.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.amount).isEqualTo(4250.0)
    }

    // endregion

    // region defect 2: generated bill without a due date

    private val reminderParser = ReminderParser()

    @Test
    fun `generated electricity bill with amount parses as an undated reminder`() {
        val result =
            reminderParser.parse(
                "JD-HDFCBK-S",
                "Your Bangalore Ele.... Ltd (BESCOM) (3011122233) bill of Rs 3582.00 is generated. " +
                    "Pay now on PayZapp. https://1.example.bank.in/HDFCBK/s/AbCdEfGh",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.dueDate).isNull()
        assertThat(result.totalDue).isEqualTo(3582.0)
    }

    @Test
    fun `generated bill notice never parses as a transaction`() {
        val result =
            parser.parse(
                "JD-HDFCBK-S",
                "Your Bangalore Ele.... Ltd (BESCOM) (3011122233) bill of Rs 3582.00 is generated. " +
                    "Pay now on PayZapp. https://1.example.bank.in/HDFCBK/s/AbCdEfGh",
            )
        assertThat(result).isNull()
    }

    @Test
    fun `a paid-bill confirmation is not a reminder`() {
        val result =
            reminderParser.parse(
                "JD-HDFCBK-S",
                "Your BESCOM bill of Rs 3582.00 has been paid successfully via PayZapp. Ref 522298765432.",
            )
        assertThat(result).isNull()
    }

    @Test
    fun `a generated bill without any amount is not a reminder`() {
        val result =
            reminderParser.parse(
                "JD-AIRTEL",
                "Bill for your Airtel Mobile has been generated and there is no payable amount this month.",
            )
        assertThat(result).isNull()
    }

    // endregion

    // region defect 3: card dispatched via courier with AWB

    private val deliveryParser = DeliveryParser()

    private val cardDispatch =
        "Congrats, Your BOBCARD is dispatched via Bluedart AWB 31198765432. " +
            "Track here https://bluedart.com/?31198765432. Download the BOBCARD app to activate your card quickly."

    @Test
    fun `dispatch notice with courier and AWB parses as an undated delivery`() {
        val result = deliveryParser.parse("JD-BOBCRD-S", cardDispatch)
        assertThat(result).isNotNull()
        assertThat(result!!.merchant).isEqualTo("Blue Dart")
        assertThat(result.reference).isEqualTo("31198765432")
        assertThat(result.expectedDate(java.time.LocalDate.of(2026, 8, 12))).isNull()
    }

    @Test
    fun `dispatch notice does not trip the scam heuristics`() {
        assertThat(ScamDetector().isScam(cardDispatch)).isFalse()
    }

    @Test
    fun `dispatched marketing copy without courier or reference is not a delivery`() {
        val result =
            deliveryParser.parse(
                "JD-OFFERS",
                "Order today and your gift hamper is dispatched in 24 hours! Hurry, offer ends soon.",
            )
        assertThat(result).isNull()
    }

    // endregion
}
