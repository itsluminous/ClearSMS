package app.clearsms.data.rules

import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.GuardId
import app.clearsms.domain.parser.GuardLibrary
import app.clearsms.domain.parser.ReminderParser
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Fixture tests for the telecom plan-expiry recharge rules
 * (jio-plan-expiry-01 / vi-plan-expiry-01, mirroring the Airtel
 * pack-expiry precedent): a plan-expiry notice is an actionable service
 * notice - IMPORTANT with the recharge sub-category - never promotional,
 * and an "expiring today / tomorrow / on <date>" deadline derives a
 * recharge reminder due on the expiry date. Every message here is
 * SYNTHETIC - shapes only, no real numbers or corpus text.
 */
class TelecomPlanExpiryRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()
    private val reminderParser = ReminderParser()
    private val anchor: LocalDate = LocalDate.of(2026, 8, 19)

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

    /**
     * Full categorizer with the sender directory's REAL stance mirrored: the
     * bundled directory files JIONET/JIOPAY as promotional, which is exactly
     * what misclassified the plan-expiry notice before the rule existed.
     */
    private fun categorize(
        sender: String,
        body: String,
    ) = MessageCategorizer(
        ruleEngine = RuleEngine(),
        senderIdLookup = SenderIdLookup { SenderInfo("JIO", Category.PROMOTIONAL, null) },
        contactLookup = ContactLookup { false },
    ).categorize(sender, body, userRules = emptyList(), builtinRules = rules, anchor = anchor)

    private val fixture =
        "Your plan for Jio number 9876543210 is expiring today. Recharge IMMEDIATELY to enjoy continued services."

    // ------------------------------------------------------------- the defect

    @Test
    fun `jio plan expiring today is an important recharge notice`() {
        val result = evaluate("JIONET", fixture)
        assertThat(result?.matchedRuleId).isEqualTo("jio-plan-expiry-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
    }

    @Test
    fun `jio plan expiry beats the promotional sender directory entry`() {
        val result = categorize("JIONET", fixture)
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.RECHARGE)
    }

    @Test
    fun `expiring today derives a recharge reminder due on the message date`() {
        val reminder = reminderParser.parse("JIONET", fixture, anchor)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.dueDate).isEqualTo(anchor)
        assertThat(reminder.type).isEqualTo(ReminderType.OTHER)
    }

    // ------------------------------------------------------------- siblings

    @Test
    fun `expiring on a date derives a reminder due that date`() {
        val body = "Your Jio plan is expiring on 25-08-2026. Recharge to keep your services active."
        val result = evaluate("JIONET", body)
        assertThat(result?.matchedRuleId).isEqualTo("jio-plan-expiry-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        val reminder = reminderParser.parse("JIONET", body, anchor)
        assertThat(reminder?.dueDate).isEqualTo(LocalDate.of(2026, 8, 25))
    }

    @Test
    fun `vi plan expiring tomorrow is an important recharge with a reminder due tomorrow`() {
        val body = "Your Vi plan is expiring tomorrow. Recharge to continue enjoying services."
        val result = evaluate("VICARE", body)
        assertThat(result?.matchedRuleId).isEqualTo("vi-plan-expiry-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
        val reminder = reminderParser.parse("VICARE", body, anchor)
        assertThat(reminder?.dueDate).isEqualTo(anchor.plusDays(1))
    }

    @Test
    fun `already expired plan is an important recharge but not a dated reminder`() {
        val body = "Your plan for Jio number 9876543210 has expired. Recharge now to continue services."
        val result = evaluate("JIONET", body)
        assertThat(result?.matchedRuleId).isEqualTo("jio-plan-expiry-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
        // A lapsed plan states no forward deadline - nothing to date a
        // reminder on, so it must not invent one.
        assertThat(reminderParser.parse("JIONET", body, anchor)).isNull()
    }

    @Test
    fun `validity expires phrasing matches the expiry rule`() {
        val result = evaluate("JIOPAY", "Your Jio number's plan validity expires today. Recharge to stay connected.")
        assertThat(result?.matchedRuleId).isEqualTo("jio-plan-expiry-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
    }

    // ----------------------------------------------------------- near-misses

    @Test
    fun `jio recharge promo blast stays promotional`() {
        val body = "Recharge with Rs.299 plan and get 2GB/day extra!"
        // The expiry rule must not steal it at the rules layer...
        assertThat(evaluate("JIONET", body)?.matchedRuleId).isNotEqualTo("jio-plan-expiry-01")
        // ...and end to end it stays promotional via the sender directory.
        val result = categorize("JIONET", body)
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
        // No reminder either: an offer is not an obligation.
        assertThat(reminderParser.parse("JIONET", body, anchor)).isNull()
    }

    @Test
    fun `jio recharge success confirmation is unregressed`() {
        // Shape the recharge rule owns: amount adjacent to "successful".
        val direct = "Recharge of Rs.299.00 is successful. Validity 28 days."
        val directResult = evaluate("JIOPAY", direct)
        assertThat(directResult?.matchedRuleId).isEqualTo("jio-recharge-01")
        assertThat(directResult?.category).isEqualTo(Category.IMPORTANT)
        assertThat(directResult?.subCategory).isEqualTo(SubCategory.RECHARGE)
        assertThat(directResult?.extracted?.get("amount")).isEqualTo("299.00")

        // Demo-corpus shape ("for your Jio number XX4210" splits the amount
        // from "successful"): baseline pin - it matched jio-promo-01 before
        // this round ("Enjoy unlimited calls and data" is the promo bait) and
        // the categorizer's transaction invariant decides its final category.
        // The expiry rule must not steal it.
        val split =
            "Recharge of Rs.299.00 for your Jio number XX4210 is successful. Validity 28 days. " +
                "Enjoy unlimited calls and data."
        assertThat(evaluate("JIOPAY", split)?.matchedRuleId).isEqualTo("jio-promo-01")
    }

    // ------------------------------------------------------------- invariant

    @Test
    fun `plan expiry carries no financial evidence artifacts`() {
        // The C3 financial-evidence invariant keys on transactional ARTIFACTS
        // (folio/UTR/SR ids, NAV, processed verbs...). A plan-expiry notice
        // has none, so the fix is rules-level: the guard must not match, and
        // it needs no widening.
        assertThat(GuardLibrary.matches(GuardId.FINANCIAL_EVIDENCE, fixture)).isFalse()
    }
}
