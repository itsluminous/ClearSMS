package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.ReminderParser
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Four service messages surfaced as PROMOTIONAL on a real inbox - a courier
 * arrival notice, a UIDAI update acknowledgement, a zero-due card statement
 * and an insurance premium deadline - while a near-identical renewal notice
 * from the same insurer sorted correctly. Each case is replayed here through
 * the FULL categorizer (bundled rules, a sender directory that files every
 * one of these headers as promotional - which the community directory does -
 * and the content parsers), so the regression pins the end-to-end category,
 * not just a rule match.
 *
 * Diagnosis the fixtures encode:
 * - No bundled rule matched any of the four; each fell through to the
 *   directory's promotional verdict. The courier body said "will deliver
 *   your shipment TODAY" (the rule knew only "will be delivered" / "arriving
 *   today"); the UIDAI body named a "demographics update" and an SRN but not
 *   the word Aadhaar; the statement put the card tail BEFORE "Statement for",
 *   said "is ready" and carried "NO PAYMENT DUE" with no due date; the
 *   premium notice stated its deadline as "make the payment before <date>",
 *   which the reminder parser did not anchor ("renewal on <date>" it did).
 * - The invariant itself had a hole: a parsed payment obligation (BILL) did
 *   not rescue a promotional directory result, so the working renewal notice
 *   only worked for a header the directory did not know.
 *
 * Every value here is SYNTHETIC: names, policy / SRN / card digits, amounts
 * and link hosts are invented; only the message STRUCTURE is real.
 */
class PromotionalMissesRegressionTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val anchor = LocalDate.of(2026, 10, 18)

    private val rules: List<RuleDefinition> by lazy {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json not found" }
        json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    /** Mirrors the community directory: every one of these headers is filed promotional. */
    private val promotionalDirectory =
        SenderIdLookup { sender ->
            val stripped = sender.uppercase().removePrefix("VM-").removeSuffix("-S")
            when (stripped) {
                "BLUDRT", "BOBCRD", "HDFCGI", "HDFCER" -> SenderInfo(stripped, Category.PROMOTIONAL, null)
                else -> null
            }
        }

    private fun categorizer(directory: SenderIdLookup = promotionalDirectory) =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = directory,
            contactLookup = { false },
        )

    private fun categorize(
        sender: String,
        body: String,
        directory: SenderIdLookup = promotionalDirectory,
    ) = categorizer(directory).categorize(sender, body, emptyList(), rules, anchor)

    // region fixtures (synthetic)

    private val courierArrival =
        "ARRIVING: Blue Dart will deliver your shipment TODAY.Track https://example.invalid/t/AB12CD .Beware of " +
            "spam from unknown numbers; never share details or open links except from verified Blue Dart numbers."

    private val uidaiUpdate =
        "Your demographics update request with SRN#S2460019988771 has been received. Please proceed with the payment -UIDAI"

    private val zeroDueStatement =
        "Your BOBCARD **4410 Statement for 03-OCT-26 is ready.  NO PAYMENT DUE (Both Total & Min. Due: Rs. 0.00 ). " +
            "View or download statement at bobcard.io/App"

    private val premiumDeadline =
        "Dear ASHA VERMA, Premium for your HDFC ERGO Health insurance policy no 1122334455667788000 is amount of " +
            "Rs.17350 Click https://example.invalid/pay/XyZ123 to make the payment before 05 Nov 2026 23:59:59"

    private val renewalNotice =
        "Your HDFC ERGO HEALTH health insurance policy no.1122334455667788 is due for renewal on 05-11-2026. Click " +
            "https://example.invalid/renew/AbC456 to renew now. For uninterrupted coverage, register for Auto-Renewal " +
            "on the payment page."

    // endregion

    @Test
    fun `courier arrival notice is a delivery, not promotional`() {
        val result = categorize("VM-BLUDRT-S", courierArrival)

        assertThat(result.matchedRuleId).isEqualTo("bluedart-delivery-01")
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `courier anti-phishing boilerplate trips neither scam nor spam`() {
        // The lead to test: the boilerplate about spam and links is advice,
        // not bait - with no rule it still reaches the directory, never SPAM.
        val result = categorize("VM-BLUDRT-S", courierArrival, directory = SenderIdLookup { null })
        assertThat(result.subCategory).isNotEqualTo(SubCategory.SCAM)
        assertThat(result.category).isNotEqualTo(Category.SPAM)
    }

    @Test
    fun `generic courier arrival wording is a delivery for an unknown courier too`() {
        val result =
            categorize(
                "VM-SOMCUR-S",
                "We will deliver your parcel today between 2pm and 6pm. Keep your phone reachable.",
                directory = SenderIdLookup { null },
            )
        assertThat(result.matchedRuleId).isEqualTo("generic-delivery-01")
        assertThat(result.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `uidai demographics update acknowledgement is a government notice`() {
        val result = categorize("VM-UIDAI-S", uidaiUpdate)

        assertThat(result.matchedRuleId).isEqualTo("uidai-aadhaar-update-01")
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.GOVERNMENT)
    }

    @Test
    fun `uidai srn alone identifies a service request`() {
        val result = categorize("VM-UIDAI-S", "Your request with SRN#S2460019988771 is under process -UIDAI")
        assertThat(result.matchedRuleId).isEqualTo("uidai-aadhaar-update-01")
    }

    @Test
    fun `zero-due card statement is an important bill with zero amounts`() {
        val result = categorize("VM-BOBCRD-S", zeroDueStatement)

        assertThat(result.matchedRuleId).isEqualTo("bobcard-stmt-02")
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result.extracted).containsEntry("total_due", "0.00")
        assertThat(result.extracted).containsEntry("min_due", "0.00")
        assertThat(result.extracted).containsEntry("account_last4", "4410")
    }

    @Test
    fun `dated statement rule and zero-due statement rule never compete`() {
        // stmt-01 needs "Pay Total ... by <date>"; stmt-02 needs "NO PAYMENT
        // DUE". Each shape matches exactly one of them.
        val dated =
            categorize(
                "VM-BOBCRD-S",
                "Statement for BOBCARD **4410 for SEP26 is generated. Pay Total: Rs 4210.5 or Min Due: Rs 310 by 13-10-26.",
            )
        assertThat(dated.matchedRuleId).isEqualTo("bobcard-stmt-01")
        assertThat(categorize("VM-BOBCRD-S", zeroDueStatement).matchedRuleId).isEqualTo("bobcard-stmt-02")
    }

    @Test
    fun `premium payment deadline is a bill like the renewal notice`() {
        for (sender in listOf("VM-HDFCGI-S", "HDFCERGO")) {
            val result = categorize(sender, premiumDeadline)
            assertWithMessage("sender $sender").that(result.category).isEqualTo(Category.IMPORTANT)
            assertWithMessage("sender $sender").that(result.subCategory).isEqualTo(SubCategory.BILL)
        }
    }

    @Test
    fun `premium payment deadline parses as an insurance reminder with amount and date`() {
        val reminder = ReminderParser().parse("HDFCGI", premiumDeadline, anchor)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.dueDate).isEqualTo(LocalDate.of(2026, 11, 5))
        assertThat(reminder.totalDue).isEqualTo(17350.0)
    }

    @Test
    fun `renewal notice keeps working for a known and an unknown header`() {
        // The contrast case. It already worked for a header the directory
        // did not know; it must ALSO work when the directory says
        // promotional - that was the hole in the invariant.
        for (sender in listOf("HDFCERGO", "VM-HDFCGI-S", "VM-HDFCER-S")) {
            val result = categorize(sender, renewalNotice)
            assertWithMessage("sender $sender").that(result.category).isEqualTo(Category.IMPORTANT)
            assertWithMessage("sender $sender").that(result.subCategory).isEqualTo(SubCategory.BILL)
        }
    }

    @Test
    fun `a dated insurance sales pitch from the same header stays promotional`() {
        // The hard edge of the obligation rescue: the pitch anchors a date
        // and scores as insurance, yet obligates nothing.
        val pitches =
            listOf(
                "Buy health insurance online and save 20%! Offer valid till 31-10-2026. T&C apply.",
                "Renew your health insurance policy today and get 10% off! Offer valid till 31-10-2026.",
                "Secure your family with health cover from Rs 500/month. Hurry, limited period offer ends 31-10-2026.",
            )
        for (pitch in pitches) {
            val result = categorize("VM-HDFCGI-S", pitch)
            assertWithMessage(pitch).that(result.category).isEqualTo(Category.PROMOTIONAL)
        }
    }

    @Test
    fun `a genuine renewal reminder without sales markers is rescued`() {
        val result =
            categorize(
                "VM-SOMINS-S",
                "Dear Customer, your motor insurance policy no MI24680135 expires on 05-11-2026. Renew before " +
                    "05-11-2026 to avoid a break in cover.",
                directory = SenderIdLookup { SenderInfo("Some Insurer", Category.PROMOTIONAL, null) },
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BILL)
    }
}
