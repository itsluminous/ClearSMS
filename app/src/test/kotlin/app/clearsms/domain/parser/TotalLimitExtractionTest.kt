package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * TOTAL credit-limit extraction (the figure that keeps outstanding and
 * utilization derivable now that manual limit entry is gone). Positive
 * shapes mirror the audited device corpora; the negative cases pin the
 * offer/loan/telecom guards so a marketing "limit" can never set a total.
 */
class TotalLimitExtractionTest {
    private val parser = TransactionParser()

    // region positive: confirmed statements

    @Test
    fun `limit changed from X to Y takes the NEW limit`() {
        val statement =
            parser.parseTotalLimit(
                "JX-ICICIT",
                "Dear Customer, The credit limit for your ICICI Bank Credit Card 4375X9012 " +
                    "has been changed from INR 100000 to INR 150000 on 2026-07-01.",
            )
        assertThat(statement).isNotNull()
        assertThat(statement!!.totalLimit).isEqualTo(150000.0)
        assertThat(statement.bankName).isEqualTo("ICICI Bank")
    }

    @Test
    fun `inline-masked card number yields the tail not the BIN`() {
        val statement =
            parser.parseTotalLimit(
                "AD-ICICIT",
                "Dear Customer, The credit limit for your ICICI Bank Credit Card 4375X9012 " +
                    "has been changed from INR 100000 to INR 150000 on 2026-07-01.",
            )
        assertThat(statement!!.accountLast4).isEqualTo("9012")
    }

    @Test
    fun `processed enhancement with new limit is - including mojibake rupee sign`() {
        val statement =
            parser.parseTotalLimit(
                "VD-HDFCBK-S",
                "Credit Limit Increased! We've processed your request to enhance limit on your " +
                    "HDFC Bank Card XX4321 Your new limit is ?1500000 Not you?Call 18002586161",
            )
        assertThat(statement).isNotNull()
        assertThat(statement!!.totalLimit).isEqualTo(1500000.0)
        assertThat(statement.accountLast4).isEqualTo("4321")
    }

    @Test
    fun `total credit limit stated directly`() {
        val statement =
            parser.parseTotalLimit(
                "VM-SBICRD",
                "Your SBI Card statement: Total Credit Limit: Rs.200,000.00 for card ending 5678.",
            )
        assertThat(statement!!.totalLimit).isEqualTo(200000.0)
    }

    @Test
    fun `sanctioned limit phrasing`() {
        val statement =
            parser.parseTotalLimit(
                "VM-AXISBK",
                "Sanctioned Limit of Rs 120000 on your Axis Bank Credit Card XX7890 is active.",
            )
        assertThat(statement!!.totalLimit).isEqualTo(120000.0)
    }

    @Test
    fun `your limit of INR phrasing`(): Unit =
        parser
            .parseTotalLimit(
                "VM-HDFCBK",
                "Spends on your HDFC Bank Credit Card XX4321 are within your limit of INR 90,000.",
            ).let { statement ->
                assertThat(statement!!.totalLimit).isEqualTo(90000.0)
            }

    // endregion

    // region negative: offers, loans, telecom, available-limit

    @Test
    fun `eligible-for increase offer is rejected`() {
        val statement =
            parser.parseTotalLimit(
                "VK-SBICRD-S",
                "Congratulations! Your SBI Credit Card 123456 is now eligible for a free of charge " +
                    "Credit Limit increase from Rs. 90,000 to Rs. 150,000. To avail, SMS INCR 1234 to 56767.",
            )
        assertThat(statement).isNull()
    }

    @Test
    fun `pre-approved card offer is rejected`() {
        val statement =
            parser.parseTotalLimit(
                "JK-KOTAKB-P",
                "Dear Customer, Enjoy unlimited movie tickets with a pre-approved PVR INOX Kotak " +
                    "Credit Card. Credit limit: Rs.300000. https://k.mbl.example T&C.",
            )
        assertThat(statement).isNull()
    }

    @Test
    fun `can-be-increased offer is rejected`() {
        val statement =
            parser.parseTotalLimit(
                "VA-IDFCFB-S",
                "Your IDFC FIRST Bank Credit Card 654321 credit limit can be increased to " +
                    "Rs 300,000 at no cost. Click to increase now: https://example T&C",
            )
        assertThat(statement).isNull()
    }

    @Test
    fun `telecom postpaid credit limit without a card is rejected`() {
        val statement =
            parser.parseTotalLimit(
                "VX-ViCARE",
                "Hello! The credit limit of your number is set at Rs. 599 and will reflect in " +
                    "your monthly Vi Postpaid bill. To increase the limit, SMS IL to 199.",
            )
        assertThat(statement).isNull()
    }

    @Test
    fun `personal loan new limit in lakhs is rejected`() {
        val statement =
            parser.parseTotalLimit(
                "VM-HDFCBN",
                "Dear Customer, You are eligible for a higher Personal Loan with HDFC Bank! " +
                    "New limit: Rs. 25 lacs. Check new EMI: example T&C",
            )
        assertThat(statement).isNull()
    }

    @Test
    fun `available credit limit is never the total`() {
        val statement =
            parser.parseTotalLimit(
                "VM-SBICRD",
                "Dear SBI Cardholder, we have received the payment via NEFT of Rs.5000.00 on " +
                    "12-07-26. Your available Credit Limit is Rs.150,000.00.",
            )
        assertThat(statement).isNull()
    }

    // endregion
}
