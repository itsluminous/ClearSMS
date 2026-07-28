package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Extraction of the issuer-reported available credit limit into
 * [app.clearsms.domain.model.ParsedTransaction.availableLimit] — the
 * dedicated field behind the credit-card headline. Phrasings mirror the
 * audited device corpora: "Avl Limit:", "Avl Lmt", "Avbl Limit:",
 * "Available limit is", "Available Credit Limit is". The limit must never
 * leak into the transaction amount (it is state, not movement).
 */
class AvailableLimitExtractionTest {
    private val parser = TransactionParser()

    /** The user-reported ICICI card-spend fixture. */
    private val iciciFixture =
        "INR 2.00 spent using ICICI Bank Card XX4001 on 27-Jul-26 on AMAZON. " +
            "Avl Limit: INR 2,87,185.45. If not you, call 1800 2662/SMS BLOCK 4001 to 9215676766."

    @Test
    fun `icici fixture extracts the available limit into its own field`() {
        val tx = parser.parse("VM-ICICIB", iciciFixture)!!
        assertThat(tx.amount).isEqualTo(2.00)
        assertThat(tx.availableLimit).isEqualTo(287185.45)
        assertThat(tx.accountLast4).isEqualTo("4001")
        // The limit is NOT a balance — semantics differ, fields stay apart.
        assertThat(tx.balance).isNull()
    }

    @Test
    fun `available limit phrasing with is`() {
        val tx =
            parser.parse(
                "VM-KOTAKB",
                "Payment of Rs.10,000.00 received on your Kotak Credit Card xx4400 on 18-07-26. " +
                    "Available limit is Rs.90,000.00.",
            )!!
        assertThat(tx.amount).isEqualTo(10000.0)
        assertThat(tx.availableLimit).isEqualTo(90000.0)
    }

    @Test
    fun `available credit limit phrasing`() {
        val tx =
            parser.parse(
                "VM-SBICRD",
                "Rs.1,500.00 spent on your SBI Credit Card ending 9012 at BIGBASKET on 20-07-26. " +
                    "Available Credit Limit is Rs.55,432.10.",
            )!!
        assertThat(tx.amount).isEqualTo(1500.0)
        assertThat(tx.availableLimit).isEqualTo(55432.10)
    }

    @Test
    fun `avl lmt abbreviation`() {
        val tx =
            parser.parse(
                "AX-AXISBK",
                "Spent Card no. XX5678 INR 1,299.00 12-07-26 19:20:11 AMAZON " +
                    "Avl Lmt INR 98,701.00 SMS BLOCK 5678 to 919951860002 - Axis Bank",
            )!!
        assertThat(tx.amount).isEqualTo(1299.0)
        assertThat(tx.availableLimit).isEqualTo(98701.0)
    }

    @Test
    fun `avbl limit abbreviation`() {
        val tx =
            parser.parse(
                "VM-ICICIB",
                "INR 750.00 spent using ICICI Bank Card XX4001 on 10-Jul-26 on SWIGGY. Avbl Limit: Rs 12,345.67.",
            )!!
        assertThat(tx.availableLimit).isEqualTo(12345.67)
    }

    @Test
    fun `limit never leaks into a foreign currency spend amount`() {
        val tx =
            parser.parse(
                "AX-AXISBK",
                "Spent USD 40.95\nAxis Bank Card no. XX5106\n20-07-26 07:40:29 IST\nUBER * PEND\nAvl Limit: INR 286368.5",
            )!!
        assertThat(tx.amount).isEqualTo(40.95)
        assertThat(tx.availableLimit).isEqualTo(286368.5)
    }

    @Test
    fun `no available limit phrase leaves the field null`() {
        val tx = parser.parse("VM-HDFCBK", "Sent Rs.500.00 From HDFC Bank A/C x8709 To SWIGGY On 12/07/26 Ref 519912345678")!!
        assertThat(tx.availableLimit).isNull()
    }
}
