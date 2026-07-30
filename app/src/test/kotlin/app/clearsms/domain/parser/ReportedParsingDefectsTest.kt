package app.clearsms.domain.parser

import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Regression fixtures for five user-reported parsing defects. Every fixture
 * is a real user message (structure preserved; digits altered).
 *
 * Decisions encoded here:
 * - A card "Txn <amt> ... At <merchant>" notification carries no debit verb,
 *   but the shape only ever announces an outgoing authorization (credits use
 *   explicit "credited"/"refund" verbs) — so it is a DEBIT.
 * - A FAILED payment moved no money: it must yield NO transaction at all.
 * - A card-network merchant token like "PTM*ZOMATO" is kept WHOLE — the `*`
 *   is a processor/merchant separator, never a truncation boundary. Only a
 *   status tail after `*` ("UBER * PEND") is stripped as noise.
 * - "bill of <amount> ... is due" announces money OWED, never money moved.
 */
class ReportedParsingDefectsTest {
    private val parser = TransactionParser()
    private val reminderParser = ReminderParser()

    // region P1 — multi-line HDFC card UPI txn

    private val hdfcCardUpiTxn =
        "Txn Rs.55.00\n" +
            "On HDFC Bank Card 9382\n" +
            "At paytm-79015682@ptybl\n" +
            "by UPI 657735305495\n" +
            "On 30-07\n" +
            "Not You?\n" +
            "Call 18002586161/SMS BLOCK CC 9382 to 7308080808"

    @Test
    fun `multi-line card upi txn parses as a debit on the card`() {
        val result = parser.parse("VM-HDFCBK", hdfcCardUpiTxn)
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.amount).isEqualTo(55.0)
        assertThat(result.accountLast4).isEqualTo("9382")
        assertThat(result.bankName).isEqualTo("HDFC Bank")
        assertThat(result.accountType).isEqualTo(AccountType.CREDIT_CARD)
    }

    @Test
    fun `multi-line card upi txn keeps the vpa as merchant and the upi reference`() {
        val result = parser.parse("VM-HDFCBK", hdfcCardUpiTxn)
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("paytm-79015682@ptybl")
        assertThat(result.referenceNumber).isEqualTo("657735305495")
        // A VPA payment is a P2P/transfer, not a store purchase.
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.TRANSFER)
    }

    @Test
    fun `otp quoting a txn amount at a merchant is still not a transaction`() {
        assertThat(
            parser.parse("HDFCBK", "Your OTP is 482910 for txn of Rs.4,500 at Amazon on your HDFC Bank Card 9382. Do not share it."),
        ).isNull()
    }

    // endregion

    // region P2 — failed payments are not debits

    private val failedElectricityPayment =
        "Hi, Electricity payment of Rs. 412.03 done against Order ID: 7484065283350749184 has failed. " +
            "Amount, if debited, will be refunded to your source a/c within 7- business days. " +
            "Click  i.airtel.in/Utilities_Tsn-history  to know the transaction status or call 400 " +
            "from your airtel number or 8800688006  in case of any query."

    @Test
    fun `failed payment yields no transaction at all`() {
        assertThat(parser.parse("AD-AIRTEL", failedElectricityPayment)).isNull()
        assertThat(parser.isFailedPayment(failedElectricityPayment)).isTrue()
    }

    @Test
    fun `matching successful electricity payment still parses as a debit`() {
        val result =
            parser.parse(
                "AD-AIRTEL",
                "Hi, Electricity payment of Rs. 412.03 done against Order ID: 7484065283350749184 is successful. " +
                    "Click  i.airtel.in/Utilities_Tsn-history  to know the transaction status.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.amount).isEqualTo(412.03)
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.UTILITY_BILL)
        // The words after "Click <url> to ..." are instructions, never a
        // merchant — with them guarded, the title falls back to the sender
        // brand (Airtel fronts the payment), which is the right label.
        assertThat(result.merchantName).isEqualTo("Airtel")
        assertThat(parser.isFailedPayment("payment is successful")).isFalse()
    }

    @Test
    fun `declined card transaction yields no transaction`() {
        assertThat(
            parser.parse("HDFCBK", "Your card transaction of Rs.500.00 at BIGBASKET was declined due to insufficient funds."),
        ).isNull()
    }

    @Test
    fun `merchant heuristic never harvests instruction text after a link`() {
        val result =
            parser.parse(
                "HDFCBK",
                "Rs.500.00 debited from A/c XX1234 on 12-07-26. Click i.example.in/Tsn-history to know the transaction status.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isNull()
    }

    // endregion

    // region P3/P4/P5 — card-network merchant tokens are kept whole

    @Test
    fun `ptm prefixed merchant token is kept whole`() {
        val result =
            parser.parse(
                "AXISBK",
                "Spent INR 854.61 / Axis Bank Card no. XX0266 / 21-06-26 11:49:11 IST / PTM*ZOMATO / Avl Limit: INR 5020.41",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("PTM*ZOMATO")
        assertThat(result.amount).isEqualTo(854.61)
        assertThat(result.accountLast4).isEqualTo("0266")
        assertThat(result.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.merchantCategory).isEqualTo(MerchantCategory.FOOD)
        assertThat(result.availableLimit).isEqualTo(5020.41)
    }

    @Test
    fun `raz prefixed merchant token is kept whole`() {
        val result =
            parser.parse(
                "AXISBK",
                "Spent INR 30    / Axis Bank Card no. XX0266 / 31-05-26 19:06:35 IST / RAZ*Zomato / Avl Limit: INR 9123.33",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("RAZ*Zomato")
        assertThat(result.amount).isEqualTo(30.0)
    }

    @Test
    fun `pyu prefixed merchant token is kept whole`() {
        val result =
            parser.parse(
                "AXISBK",
                "Spent INR 810.33/ Axis Bank Card no. XX0266 / 08-05-26 20:51:54 IST / PYU*ZOMATO / Avl Limit: INR 11241.16",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("PYU*ZOMATO")
        assertThat(result.amount).isEqualTo(810.33)
    }

    @Test
    fun `status tail after the star is still stripped as noise`() {
        // Reconciliation: "UBER * PEND" carries a card-network STATUS after
        // the star, not a merchant — there the tail is noise and the
        // merchant is the part before it.
        val result =
            parser.parse(
                "AXISBK",
                "Spent USD 40.95\nAxis Bank Card no. XX5106\n20-07-26 07:40:29 IST\nUBER * PEND\nAvl Limit: INR 286368.5",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("Uber")
    }

    @Test
    fun `plain single-token merchant line is unaffected`() {
        val result =
            parser.parse(
                "AXISBK",
                "Spent INR 199.00 / Axis Bank Card no. XX0266 / 01-06-26 10:00:00 IST / ZOMATO / Avl Limit: INR 5000.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.merchantName).isEqualTo("Zomato")
    }

    // endregion

    // region P6 — broadband bill is a reminder, not a transaction

    private val actFibernetBill =
        "Hi Prakashkumar, your ACT Fibernet Broadband bill of Rs.1178.82 for 102017641550 is due on " +
            "10-Jun-26. Pay now on Airtel App i.airtel.in/Ly5ilUnFB3b to avoid late fees. " +
            "Ignore if already paid. Thank you!"

    @Test
    fun `broadband bill due notice is not a transaction`() {
        assertThat(parser.parse("AD-AIRBIL", actFibernetBill)).isNull()
        assertThat(parser.isStatementNotice(actFibernetBill)).isTrue()
    }

    @Test
    fun `broadband bill due notice is a bill reminder with amount date and biller`() {
        val reminder = reminderParser.parse("AD-AIRBIL", actFibernetBill)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.type).isEqualTo(ReminderType.OTHER)
        assertThat(reminder.totalDue).isEqualTo(1178.82)
        assertThat(reminder.dueDate).isEqualTo(LocalDate.of(2026, 6, 10))
        assertThat(reminder.label).isEqualTo("ACT Fibernet Broadband bill")
    }

    @Test
    fun `completed broadband bill payment still parses as a transaction`() {
        val result =
            parser.parse(
                "AD-AIRBIL",
                "Rs.1,178.82 paid towards ACT Fibernet Broadband bill via UPI from A/c XX1234 on 05-06-26.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.amount).isEqualTo(1178.82)
    }

    @Test
    fun `bundled act bill-due rule categorizes the notice as a bill with extracts`() {
        val assetFile =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).first { it.exists() }
        val document =
            Json { ignoreUnknownKeys = true }
                .decodeFromString(RuleDocument.serializer(), assetFile.readText())
        val result = RuleEngine().evaluate(document.rules, sender = "AD-AIRBIL", body = actFibernetBill)
        assertThat(result).isNotNull()
        assertThat(result!!.matchedRuleId).isEqualTo("act-bill-due-01")
        assertThat(result.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result.extracted["total_due"]).isEqualTo("1178.82")
        assertThat(result.extracted["due_date"]).isEqualTo("10-Jun-26")
    }

    // endregion
}
