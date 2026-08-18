package app.clearsms.data.rules

import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.TransactionDeduplication
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.model.date
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.TransactionParser
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Financial correspondence must never classify PROMOTIONAL - and each shape
 * deserves its RIGHT classification, with an explicit transaction/reminder
 * derivation decision. One test per reported shape (all bodies are synthetic
 * reconstructions - no real names, tails or reference numbers), asserting:
 *
 * - which bundled rule claims it, and the final category + sub-category;
 * - whether a transaction or reminder derives, and why (in comments).
 *
 * Derivation decisions, summarized:
 * - Card refund initiated/PROCESSED (payout in flight): NO transaction - the
 *   money lands via the receiving bank's own credit SMS; deriving here would
 *   double-count (the `payout_in_flight` guard enforces this in the parser).
 * - PayU merchant settlement notice: NO transaction - aggregator-side noise;
 *   the settlement credit arrives from the bank with account and balance.
 * - MF SIP instalment with units allotted: DERIVES a debit (consistent with
 *   the existing MF purchase handling - it is the user's own contribution).
 * - TDS summary: never a transaction (cumulative figures, no money moved).
 * - Generated hospital bill: a BILL with its amount; undated, so the
 *   generated-bill carve-out carries it into Alerts.
 * - Order confirmation: order + amount is not a payment - no transaction.
 * - myBillBook "payment recorded": NO transaction - a third-party ledger
 *   echo of a payment whose authoritative record is the bank's own SMS
 *   (no account/bank/reference here means dedup could never collapse it).
 * - MF redemption processed: NO credit yet - payout is in flight; the bank
 *   credit SMS carries the money when it lands.
 * - Groww settlement with UTR: DERIVES the credit - the UTR gives the
 *   reference-match dedup tier a key, so the bank's own credit SMS for the
 *   same UTR collapses into one row instead of double-counting.
 */
class FinancialCorrespondenceRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()
    private val transactionParser = TransactionParser()
    private val reminderParser = ReminderParser()

    /** Fixed message-date anchor so yearless dates resolve deterministically. */
    private val anchor = LocalDate.of(2026, 8, 8)

    private val rules: List<RuleDefinition> by lazy {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json not found" }
        json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    private fun evaluate(
        sender: String,
        body: String,
    ) = engine.evaluate(rules, sender, body, anchor)

    // region SBI Card credit-balance refund lifecycle (fixtures 1 + 2)

    private val refundInitiated =
        "Refund of Rs.1,240.50 towards credit balance on your SBI Card ending 5416 has been " +
            "initiated. It will be credited to your linked bank account within 7 working days. SR 482913605."

    private val refundProcessed =
        "Refund of Rs.1,240.50 towards credit balance on your SBI Card ending 5416 has been " +
            "processed on 14-08-26 and will reflect in your linked bank account within 5 working days."

    @Test
    fun `sbi card refund initiated is an important bank alert`() {
        val result = evaluate("SBICRD", refundInitiated)
        assertThat(result?.matchedRuleId).isEqualTo("sbi-card-refund-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `sbi card refund initiated derives no transaction - money has not landed`() {
        // "Refund" is a credit keyword; without the payout_in_flight veto the
        // parser would fabricate a credit that the savings account's own
        // credit SMS records again when the money lands.
        assertThat(transactionParser.parse("SBICRD", refundInitiated)).isNull()
    }

    @Test
    fun `sbi card refund PROCESSED still derives no credit - payout is in flight`() {
        // Decision: "processed" here means it LEFT the card account; the
        // quoted tail is the CARD's while the money lands in the linked
        // bank account, whose own credit SMS is the single record of it -
        // deriving here would double-count AND misattribute the credit to
        // the card. (A merchant refund "processed to your account" is the
        // opposite precedent and still derives - see the Flipkart fixtures.)
        val result = evaluate("SBICRD", refundProcessed)
        assertThat(result?.matchedRuleId).isEqualTo("sbi-card-refund-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(transactionParser.parse("SBICRD", refundProcessed)).isNull()
    }

    @Test
    fun `a refund actually credited still parses as the credit it is`() {
        // The veto is narrow: no "initiated", and no future-landing clause -
        // this money LANDED, and this message is its record.
        val landed = "Refund of Rs.1,240.50 credited to your account XX5416 on 14-08-26. Ref 482913605."
        val parsed = transactionParser.parse("SBICRD", landed)
        assertThat(parsed).isNotNull()
        assertThat(parsed?.type).isEqualTo(TransactionType.CREDIT)
    }

    // endregion

    // region PayU merchant settlement (fixture 3)

    private val payuSettlement =
        "Settlement of Rs.56.00 for card transaction at your store has been processed via PayU. " +
            "SR 208114522. Amount will be credited to your registered account by the next working day."

    @Test
    fun `payu merchant settlement notice is an important bank alert with no transaction`() {
        // Decision: merchant-side settlement noise. The money reaches the
        // bank at T+1 via the bank's own credit SMS (with account and
        // balance); a row here would double-count and could never dedup
        // (no shared account tail or reference with the bank's message).
        val result = evaluate("PAYUIN", payuSettlement)
        assertThat(result?.matchedRuleId).isEqualTo("payu-merchant-settlement-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(transactionParser.parse("PAYUIN", payuSettlement)).isNull()
    }

    // endregion

    // region MF SIP instalments with units allotted (fixtures 4 + 11)

    @Test
    fun `invesco sip instalment with units allotted derives an investment debit`() {
        // The MF-rule miss was the SENDER alternation: the body shape was
        // already covered by mf-purchase-generic-01, but Invesco (INVMFS)
        // and quant (QUANTM) were absent from every MF rule's sender list.
        val result =
            evaluate(
                "INVMFS",
                "SIP Instalment of Rs.5,000.00 processed in Folio 5109876321 - Invesco India Flexi Cap " +
                    "Fund - Direct Growth. 42.318 units allotted at NAV of Rs.118.15 on 14-08-26.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("mf-purchase-generic-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.MUTUAL_FUND)
        assertThat(result?.extracted).containsEntry("amount", "5,000.00")
        assertThat(result?.extracted).containsEntry("type", "debit")
    }

    @Test
    fun `quant sip instalment with units allotted derives an investment debit`() {
        val result =
            evaluate(
                "QUANTM",
                "Your SIP instalment of Rs.2,500.00 in Folio 5023/47 under quant Small Cap Fund - " +
                    "Direct Plan Growth has been processed. Units allotted: 9.827 at NAV Rs.254.41.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("mf-purchase-generic-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.MUTUAL_FUND)
        assertThat(result?.extracted).containsEntry("amount", "2,500.00")
        assertThat(result?.extracted).containsEntry("type", "debit")
    }

    // endregion

    // region TDS quarterly summary (fixture 5)

    private val tdsSummary =
        "Total TDS by all deductors of PAN ABXPK1234X for Qtr ending 30-Jun-26 is Rs 48,500 and " +
            "cumulative TDS for FY 26-27 is Rs 48,500. View 26AS for details - Income Tax Department."

    @Test
    fun `tds quarterly summary is important government - never a transaction`() {
        // Cumulative figures: no money moved by this message.
        val result = evaluate("ITDEPT", tdsSummary)
        assertThat(result?.matchedRuleId).isEqualTo("itd-tds-summary-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.GOVERNMENT)
        assertThat(transactionParser.parse("ITDEPT", tdsSummary)).isNull()
        assertThat(reminderParser.parse("ITDEPT", tdsSummary, anchor)).isNull()
    }

    // endregion

    // region Hospital bill with link (fixture 6)

    private val hospitalBill =
        "Dear RAVI KUMAR, bill of Rs.5,400.00 has been generated for OP consultation at Sakra " +
            "World Hospital. View and pay at sakrahospital.example/pay/ZK18M2"

    @Test
    fun `generated hospital bill is an important bill with its amount extracted`() {
        // The BESCOM precedent: an undated generated bill carrying its amount
        // IS the obligation - the generated-bill carve-out lets it into
        // Alerts without a due date.
        val result = evaluate("SAKRAH", hospitalBill)
        assertThat(result?.matchedRuleId).isEqualTo("hospital-bill-generated-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted).containsEntry("total_due", "5,400.00")
        assertThat(reminderParser.isGeneratedBillNotice(hospitalBill)).isTrue()
        assertThat(transactionParser.parse("SAKRAH", hospitalBill)).isNull()
    }

    // endregion

    // region Dominos order confirmation (fixture 7)

    @Test
    fun `dominos order confirmation is important delivery - order plus amount is not a payment`() {
        val body =
            "Your order no. 10784536 is confirmed! Total Rs.599.00. Your pizza will be delivered " +
                "in about 30 mins. Track at dominos.co.in/track"
        val result = evaluate("DOMINO", body)
        assertThat(result?.matchedRuleId).isEqualTo("dominos-order-confirmed-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
        assertThat(transactionParser.parse("DOMINO", body)).isNull()
    }

    @Test
    fun `dominos BOGO promo does not match the order confirmation rule`() {
        val result =
            evaluate(
                "DOMINO",
                "Weekend Offer! Buy 1 Get 1 FREE on medium pizzas. Use code NEWUSER at " +
                    "dominos.co.in. Hurry, offer ends Sunday!",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("dominos-order-confirmed-01")
    }

    // endregion

    // region myBillBook payment recorded (fixture 8)

    private val billBookRecorded =
        "Payment of Rs.2,500.00 recorded against Invoice INV-2214 to Sharma Traders on 14-08-26 " +
            "via myBillBook. View receipt at mybillbook.example/r/9q2"

    @Test
    fun `mybillbook payment recorded is an important bank alert with no transaction`() {
        // Decision: a third-party ledger echo of a payment the user made TO
        // the merchant. The payer's own bank SMS is the authoritative debit
        // (with account, tail and balance); this record shares no reference
        // or account with it, so dedup could never collapse the pair -
        // deriving here would double-count.
        val result = evaluate("MYBLBK", billBookRecorded)
        assertThat(result?.matchedRuleId).isEqualTo("mybillbook-payment-recorded-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(transactionParser.parse("MYBLBK", billBookRecorded)).isNull()
    }

    // endregion

    // region ABSL MF redemption processed (fixture 9)

    private val redemptionProcessed =
        "Redemption of Rs.25,000.00 from Aditya Birla SL Liquid Fund, Folio 5098765432, has been " +
            "processed on 14-08-26. Amount will be credited to your registered bank account within 3 working days."

    @Test
    fun `mf redemption processed is important investment with no credit yet`() {
        // Payout initiated = no credit yet: the bank's own credit SMS carries
        // the money when it lands (and is its single record).
        val result = evaluate("ABSLMF", redemptionProcessed)
        assertThat(result?.matchedRuleId).isEqualTo("mf-redemption-processed-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.MUTUAL_FUND)
        assertThat(transactionParser.parse("ABSLMF", redemptionProcessed)).isNull()
    }

    // endregion

    // region IndiGo yearless booking date (fixture 10)

    @Test
    fun `indigo booking with yearless 10Aug date anchors on the message date`() {
        val result =
            evaluate(
                "INDIGO",
                "Dear Customer, we are happy to confirm your booking under PNR - GHT6Y2, 10Aug, from " +
                    "PAT to BLR(T1), 6E 6433 at 18.10 hrs. Web check-in opens 48 hrs before departure - IndiGo",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-pnr-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(result?.extracted).containsEntry("pnr", "GHT6Y2")
        assertThat(result?.extracted).containsEntry("route", "PAT to BLR(T1)")
        // Anchor (message date) is 2026-08-08, so yearless "10Aug" resolves
        // to 2026-08-10 - never a phantom future year.
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 10))
    }

    @Test
    fun `dated booking confirmations still extract their full date`() {
        val result =
            evaluate(
                "INDIGO",
                "Dear Mr Kumar, we are happy to confirm your booking under PNR - ZXCHRQ, " +
                    "25 Aug 26, from BLR(T1) to DEL, 6E 2134 at 08.15 hrs.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-pnr-01")
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 25))
    }

    // endregion

    // region Groww settlement with UTR (fixture 12)

    private val growwSettlement =
        "Monthly settlement of Rs.1,842.00 has been credited to your HDFC Bank account XX4522 " +
            "via UTR 522817364950. - Groww"

    @Test
    fun `groww settlement with utr derives the credit with a dedup-able reference`() {
        // Decision: this IS the credit landing (completed tense, bank,
        // tail). The bank will also SMS it - but the UTR keys the
        // reference-match dedup tier, so both records collapse into one row.
        val result = evaluate("GROWWZ", growwSettlement)
        assertThat(result?.matchedRuleId).isEqualTo("groww-settlement-credit-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted).containsEntry("amount", "1,842.00")
        assertThat(result?.extracted).containsEntry("type", "credit")
        assertThat(result?.extracted).containsEntry("account_last4", "4522")
        assertThat(result?.extracted).containsEntry("bank", "HDFC Bank")
        assertThat(result?.extracted).containsEntry("reference", "522817364950")
    }

    @Test
    fun `groww settlement and the bank's own credit sms are one payment under reference dedup`() {
        val growwRow =
            TransactionEntity(
                amount = 1842.0,
                type = TransactionType.CREDIT,
                accountNumber = "4522",
                bankName = "HDFC Bank",
                timestamp = 1_000_000L,
                referenceNumber = "522817364950",
                rawSmsId = 1,
            )
        val bankRow =
            growwRow.copy(
                timestamp = 1_000_000L + 3 * 60 * 60 * 1000L,
                rawSmsId = 2,
                balance = 61_921.15,
            )
        assertThat(TransactionDeduplication.isReferenceDuplicate(growwRow, bankRow)).isTrue()
    }

    // endregion
}
