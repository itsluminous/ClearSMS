package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Proves the data-driven guards are EXACTLY equivalent to the Kotlin regex
 * constants they replaced. [FROZEN] holds verbatim copies of the deleted
 * constants; every guard must agree with its frozen original on a fixture
 * set that exercises each pattern, and - when a device corpus is present at
 * [CORPUS] (kept under /tmp, never in the repo) - on every real message
 * body. Any disagreement is a bug in the migration, not a rounding error.
 */
class GuardEquivalenceTest {
    @Test
    fun `every guard agrees with its frozen constant on the fixture set`() {
        for ((id, frozen) in FROZEN) {
            for (body in FIXTURES) {
                val capped = body.take(GuardLibrary.MAX_INPUT_LENGTH)
                assertWithMessage("guard '${id.id}' disagrees with the legacy constant on a fixture")
                    .that(GuardLibrary.matches(id, body))
                    .isEqualTo(frozen.containsMatchIn(capped))
            }
        }
    }

    @Test
    fun `statement notice scrub output matches the frozen constant on the fixture set`() {
        val frozen = FROZEN.getValue(GuardId.STATEMENT_NOTICE)
        for (body in FIXTURES) {
            assertWithMessage("scrub output diverged from the legacy replace")
                .that(GuardLibrary.scrub(GuardId.STATEMENT_NOTICE, body))
                .isEqualTo(frozen.replace(body, " "))
        }
    }

    @Test
    fun `every guard agrees with its frozen constant on the device corpus`() {
        val corpus = File(CORPUS)
        assumeTrue("no device corpus at $CORPUS; skipping", corpus.isFile)
        val bodies =
            corpus.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    Json
                        .parseToJsonElement(line)
                        .jsonObject["body"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            }
        assumeTrue("corpus empty", bodies.isNotEmpty())

        val frozenStatement = FROZEN.getValue(GuardId.STATEMENT_NOTICE)
        val report = StringBuilder("guard equivalence over ${bodies.size} corpus messages (old vs new match counts)\n")
        for ((id, frozen) in FROZEN) {
            var oldCount = 0
            var newCount = 0
            var disagreements = 0
            for (body in bodies) {
                val capped = body.take(GuardLibrary.MAX_INPUT_LENGTH)
                val old = frozen.containsMatchIn(capped)
                val new = GuardLibrary.matches(id, body)
                if (old) oldCount++
                if (new) newCount++
                if (old != new) disagreements++
                if (id == GuardId.STATEMENT_NOTICE && old) {
                    assertWithMessage("scrub output diverged on a corpus message")
                        .that(GuardLibrary.scrub(GuardId.STATEMENT_NOTICE, capped))
                        .isEqualTo(frozenStatement.replace(capped, " "))
                }
            }
            report.append("  ${id.id}: old=$oldCount new=$newCount disagreements=$disagreements\n")
            assertWithMessage("guard '${id.id}' disagrees with the legacy constant on the corpus")
                .that(disagreements)
                .isEqualTo(0)
        }
        // Counts only - never message content.
        File(corpus.parentFile, "refactor2-guard-counts.txt").writeText(report.toString())
    }

    private companion object {
        /** Optional local corpus (JSONL of {"sender","body"}), never committed. */
        const val CORPUS = "/tmp/clearsms/guard-corpus.jsonl"

        /**
         * Verbatim copies of the deleted Kotlin constants, updated in lockstep
         * with deliberate guard-content changes (see the round-T comments
         * inline) so the corpus equivalence check freezes CURRENT intent.
         */
        val FROZEN: Map<GuardId, Regex> =
            mapOf(
                GuardId.STATEMENT_NOTICE to
                    Regex(
                        "(?i)\\b(?:e-?)?statement\\s+(?:is|has\\s+been|was)\\s+" +
                            "(?:sent|generated|mailed|e-?mailed|dispatched)|" +
                            "\\b(?:e-?)?statement\\s+of\\b[^\\n]{0,80}?\\bhas\\s+been\\s+(?:sent|mailed|e-?mailed)|" +
                            "\\b(?:e-?)?statement\\s+(?:is\\s+)?(?:now\\s+)?(?:available|ready)\\b",
                    ),
                GuardId.BILL_DUE_NOTICE to
                    Regex(
                        "(?i)\\b(?:payment|bill)\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,]+(?:\\.\\d{1,2})?[^\\n]{0,100}?\\bis\\s+due\\b|" +
                            // Round T: the "ignore if paid" advisory only appears on
                            // reminders; its "paid" verb otherwise fakes a debit.
                            "\\bignore\\s+if\\s+(?:already\\s+)?paid\\b",
                    ),
                GuardId.FAILED_PAYMENT to
                    Regex(
                        "(?i)\\bhas\\s+failed\\b|" +
                            "\\b(?:payment|transaction|txn|transfer|recharge)\\s+(?:has\\s+|was\\s+)?failed\\b|" +
                            "\\bcould\\s+not\\s+be\\s+(?:processed|completed)\\b|" +
                            "\\b(?:was\\s+)?declined\\b|" +
                            "\\bunsuccessful\\b",
                    ),
                GuardId.SETTLED_PAYMENT to
                    Regex(
                        "(?i)payment\\s+(?:of\\s+\\S{0,20}\\s*)?(?:received|successful|processed)|" +
                            "\\breceived\\s+(?:your\\s+)?payment|" +
                            "successfully\\s+(?:paid|processed|credited|received)|" +
                            "\\bpaid\\s+successfully|" +
                            "thank\\s+you\\s+for\\s+(?:an?\\s+|your\\s+)?(?:online\\s+)?(?:payment|paying)|" +
                            "payment\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*(?:Dr\\.?\\s*)?([\\d,]+(?:\\.\\d{1,2})?)" +
                            "[^\\n]{0,80}?(?:transaction\\s+ref|txn\\s+ref|\\bUTR\\b)|" +
                            "payment\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*(?:Dr\\.?\\s*)?([\\d,]+(?:\\.\\d{1,2})?)" +
                            "[^\\n]{0,60}?\\bwas\\s+(?:received|made)|" +
                            "has\\s+been\\s+(?:paid|received|credited|processed|settled|reimbursed|refunded)|" +
                            "\\breimburse(?:d|ment)\\b|\\brefund(?:ed)?\\b|\\bsettled\\b|" +
                            "\\bclaim\\s+(?:of|amount|no\\.?|number|id)\\b|" +
                            // Round T: future-tense "will/shall/would be debited" is an
                            // upcoming obligation, not a settled event.
                            "(?<!will be )(?<!shall be )(?<!would be )\\bdebited\\b|\\bcredited\\b",
                    ),
                GuardId.MARKETING_PITCH to
                    Regex(
                        "(?i)\\breap\\s+benefits?\\b|\\brising\\s+(?:capital\\s+|stock\\s+)?markets?\\b|" +
                            "\\bwealth\\s+creation\\b|\\bmarket[-\\s]linked\\s+returns?\\b|" +
                            "\\bgrow\\s+your\\s+(?:money|wealth|savings)\\b|\\bwhy\\s+invest\\b|" +
                            "\\bbest\\s+time\\s+to\\s+invest\\b|\\binvest\\s+today\\b|\\bstart\\s+investing\\b",
                    ),
                GuardId.VOUCHER to
                    Regex("(?i)\\bvouchers?\\b|\\bcoupons?\\b|\\bgift\\s*card\\b|\\bpromo\\s+code\\b"),
                GuardId.MANDATE_NOTICE to
                    Regex(
                        "(?i)\\bmandate\\b[\\s\\S]{0,60}?\\bsuccessfully\\s+(?:created|cancelled|revoked|modified)\\b|" +
                            "\\bsuccessfully\\s+cancelled\\s+the\\s+scheduled\\b[\\s\\S]{0,60}?\\bpayment\\b|" +
                            "\\bmandate\\s+(?:has\\s+been|is|was)\\s+(?:created|cancelled|revoked|modified)\\b",
                    ),
                GuardId.LIMIT_OFFER to
                    Regex(
                        "(?i)\\b(?:eligible|pre-?approved|can\\s+be\\s+(?:increased|enhanced)|to\\s+avail|avail\\s+now|apply\\s+now)\\b",
                    ),
                GuardId.TIER_PREMIUM to
                    Regex("(?i)\\b[\\w&+.]+\\s+premium\\s+(?:subscription|plan|membership|pack|account|is\\s+now\\s+active)\\b"),
                GuardId.FUTURE_TENSE to
                    Regex("(?i)\\b(?:will|shall|would)\\s+be\\s*$"),
                GuardId.INSTRUCTION_START to
                    Regex(
                        "(?i)^(?:know|check|view|track|see|get|download|install|update|complete|continue|avoid|claim|apply|visit|click|login)\\b",
                    ),
            )

        /**
         * Fixture bodies exercising every pattern of every guard, plus
         * near-misses that must NOT match. Digits are synthetic.
         */
        val FIXTURES =
            listOf(
                "Statement is sent to your registered email id",
                "E-statement of your HDFC Bank Credit Card XX1234 has been mailed to you",
                "Your monthly e-statement was generated on 01-08-26",
                "Your statement is now available. Total of Rs 15,240 is due",
                "Statement ready for download",
                "Payment of INR 532.62 for your Axis Bank Credit Card is due on 04-04-26. Ignore if paid",
                "your ACT Fibernet Broadband bill of Rs.1178.82 for 102017641550 is due on 10-Jun-26",
                "Bill of Rs.890 for consumer 12345 is due by 15-08-26",
                "Your payment of Rs.500 to Airtel has failed. If debited, amount will be refunded",
                "Transaction of INR 2,499 was declined due to insufficient balance",
                "Your recharge failed. Please retry",
                "Txn could not be completed at this time",
                "UPI transfer unsuccessful. Ref 424817849668",
                "We have received your payment of Rs.5,000 towards your card",
                "Payment received. Thank you",
                "Payment of Rs.1,178.82 was received via NEFT",
                "Payment of Rs 549 done, transaction ref ABC123XYZ",
                "Rs.10,000 successfully credited to your account",
                "Your bill has been paid successfully",
                "Thank You for an online payment of Rs.549.00 towards your card",
                "Your claim of Rs 2,000 has been reimbursed",
                "Refund of Rs.239 processed",
                "Amount settled with the merchant",
                "Rs.90.00 debited from A/c *8709 to SITHARAMA on 04-09 Ref 424817849668",
                "INR 40,194.56 credited to your salary account",
                "investment in ULIPs can reap benefits of rising capital markets. Invest today!",
                "Grow your wealth with market-linked returns. Why invest? Wealth creation made easy",
                "Best time to invest! Start investing with Rs.500",
                "Your Rs.500 Amazon voucher from your Flipkart Axis Credit Card expires on 30-09-26",
                "Coupons worth Rs.200 inside! Use promo code SAVE20",
                "Redeem your gift card before 31-08-26",
                "Mandate for Rs.649.00 towards Netflix successfully created on your account",
                "You have successfully cancelled the scheduled monthly payment of Rs.299",
                "Your UPI Autopay mandate has been revoked",
                "E-mandate is created for SIP of Rs.5,000",
                "You are eligible for a Credit Limit increase up to Rs.3,00,000. Apply now!",
                "Pre-approved offer: your limit can be increased to Rs 2,50,000. To avail, click",
                "The credit limit for your Card 1234X5678 has been changed from INR 100000 to INR 150000",
                "Your new limit is ?1500000",
                "Your LIV Premium subscription is confirmed for Rs.999",
                "YouTube Premium plan renews on 05-08-26 for Rs.129",
                "Sony LIV Premium pack is now active",
                "premium of Rs.24,000 is due on 05-May-26 for your iProtect Smart policy no H123",
                "Renewal premium of Rs 5,000 will be deducted from your account",
                "an EMI of Rs 4,131 will be ",
                "amount shall be ",
                "Rs.500 will be auto-debited tomorrow",
                "know the transaction status",
                "check your balance instantly",
                "avoid late fees by paying today",
                "Amazon Pay India Pvt Ltd",
                "Uber India Systems",
                "Amt Sent Rs.90.00 From HDFC Bank A/C *8709 On 04-09",
                "OTP for txn of Rs 4,999 at Amazon is 482913. Do not share",
                "Recharge of Rs.239.00 successful. Data balance 1.5GB",
                "Total due Rs.15,240. Min due Rs.762. Pay by 05-08-26 to avoid late fee",
                "",
                "   ",
                "a".repeat(2000),
                "will be " + "x".repeat(1200),
            )
    }
}
