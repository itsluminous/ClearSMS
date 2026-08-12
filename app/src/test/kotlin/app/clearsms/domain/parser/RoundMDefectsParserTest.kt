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
}
