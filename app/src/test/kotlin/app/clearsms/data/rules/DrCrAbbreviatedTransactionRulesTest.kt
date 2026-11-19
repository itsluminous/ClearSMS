package app.clearsms.data.rules

import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.rules.SenderRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * GitHub #166: Bank of Baroda's UPI alerts abbreviate the verbs - "Rs.X Dr.
 * from A/C XXXXXXNNNN and Cr. to <vpa>" - while every bundled BOB rule (and
 * the heuristic [app.clearsms.domain.parser.TransactionParser]) only knew the
 * words "debited"/"credited". The message fell through to the sender
 * directory's bare IMPORTANT verdict with NO extracts, so Finance never saw a
 * transaction (only the balance statement refreshed the account).
 *
 * Two layers fix it: a BOB-specific pair at the bank band (300) that also
 * captures the counterparty VPA, the bank and the balance, and a generic
 * `Dr.`/`Cr.` pair in the generic band (36) for any other bank using the
 * same abbreviation. The generic pair sits below EVERY sender-specific
 * transaction rule in the asset, so it can never displace one.
 *
 * Every body here is SYNTHETIC: the VPA, account tail, reference, balance
 * and amounts were invented; only the message SHAPE is real.
 */
class DrCrAbbreviatedTransactionRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()
    private val anchor = LocalDate.of(2026, 11, 11)

    private val rules: List<RuleDefinition> by lazy {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json not found" }
        json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    /** Mirrors the community directory: BOB headers are filed IMPORTANT with no sub-category. */
    private val directory =
        SenderIdLookup { sender ->
            val core =
                sender
                    .uppercase()
                    .removePrefix("VM-")
                    .removePrefix("VK-")
                    .removePrefix("AD-")
                    .removeSuffix("-S")
            if (core in setOf("BOBSMS", "BOBTXN", "BOBUPI")) SenderInfo(core, Category.IMPORTANT, null) else null
        }

    private fun categorize(
        sender: String,
        body: String,
    ) = MessageCategorizer(engine, directory, { false }).categorize(sender, body, emptyList(), rules, anchor)

    private fun evaluate(
        sender: String,
        body: String,
    ) = engine.evaluate(rules, sender, body, anchor)

    private val bobUpiDebit =
        "Rs.78.00 Dr. from A/C XXXXXX4421 and Cr. to shopkeeper.demo@ptys. Ref:512345678901. " +
            "AvlBal:Rs1234.56(2026:11:11 10:28:46). Not you? Call 18005700/5000-BOB"

    private val bobUpiCredit =
        "Rs.250.00 Cr. to A/C XXXXXX4421 and Dr. from friend.demo@okaxis. Ref:512345678902. " +
            "AvlBal:Rs1484.56(2026:11:12 09:01:02). Not you? Call 18005700/5000-BOB"

    @Test
    fun `bob upi debit sorts as a transaction with every field through the full categorizer`() {
        for (sender in listOf("BOBSMS", "VM-BOBSMS", "BOBTXN", "VK-BOBSMS-S", "AD-BOBUPI-S")) {
            val result = categorize(sender, bobUpiDebit)
            assertWithMessage(sender).that(result.matchedRuleId).isEqualTo("bob-upi-debit-01")
            assertWithMessage(sender).that(result.category).isEqualTo(Category.IMPORTANT)
            assertWithMessage(sender).that(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
            assertThat(result.extracted["amount"]).isEqualTo("78.00")
            assertThat(result.extracted["account_last4"]).isEqualTo("4421")
            assertThat(result.extracted["type"]).isEqualTo("debit")
            assertThat(result.extracted["bank"]).isEqualTo("Bank of Baroda")
            assertThat(result.extracted["merchant"]).isEqualTo("shopkeeper.demo@ptys")
            assertThat(result.extracted["balance"]).isEqualTo("1234.56")
        }
    }

    @Test
    fun `bob upi credit sorts as a credit with the payer vpa as merchant`() {
        val result = categorize("VM-BOBSMS", bobUpiCredit)
        assertThat(result.matchedRuleId).isEqualTo("bob-upi-credit-01")
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result.extracted["amount"]).isEqualTo("250.00")
        assertThat(result.extracted["account_last4"]).isEqualTo("4421")
        assertThat(result.extracted["type"]).isEqualTo("credit")
        assertThat(result.extracted["merchant"]).isEqualTo("friend.demo@okaxis")
        assertThat(result.extracted["balance"]).isEqualTo("1484.56")
    }

    @Test
    fun `bob upi debit without a vpa still extracts amount account and balance`() {
        val result =
            evaluate(
                "BOBTXN",
                "Rs.1,200.00 Dr. from A/C XXXXXX4421. Ref:512345678903. AvlBal:Rs34.56(2026:11:13 08:00:00). Not you? Call 18005700/5000-BOB",
            )
        assertThat(result?.matchedRuleId).isEqualTo("bob-upi-debit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("1,200.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("4421")
        assertThat(result?.extracted?.containsKey("merchant")).isFalse()
        assertThat(result?.extracted?.get("balance")).isEqualTo("34.56")
    }

    @Test
    fun `the old long-form bob debit still goes to its original rule`() {
        val result =
            evaluate(
                "VM-BOBSMS",
                "Rs.500.00 debited from a/c XX4421 on 11-11-26 to VPA demo@upi. Ref 512345678904. Avl Bal Rs 700.00 -BOB",
            )
        assertThat(result?.matchedRuleId).isEqualTo("bob-debit-01")
    }

    @Test
    fun `the generic dr cr pair covers an unknown bank using the same abbreviation`() {
        val debit = categorize("AX-UNKBNK-S", "Rs.310.00 Dr. from A/c XX7788 on 11-11-26 to UPI demo@okbank. Avl Bal Rs 1,000.00")
        assertThat(debit.matchedRuleId).isEqualTo("generic-debit-05")
        assertThat(debit.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(debit.extracted["amount"]).isEqualTo("310.00")
        assertThat(debit.extracted["account_last4"]).isEqualTo("7788")
        assertThat(debit.extracted["type"]).isEqualTo("debit")

        val credit = categorize("AX-UNKBNK-S", "INR 2,000.00 is Cr. to your account no. XX7788 by NEFT. Bal INR 3,000.00")
        assertThat(credit.matchedRuleId).isEqualTo("generic-credit-03")
        assertThat(credit.extracted["amount"]).isEqualTo("2,000.00")
        assertThat(credit.extracted["type"]).isEqualTo("credit")
    }

    @Test
    fun `the generic pair can never displace a sender-specific transaction rule`() {
        // Structural proof: in the shipped asset the generic/ATM safety net
        // tops out below every sender-bound transaction rule, and the engine
        // picks the highest priority that matches.
        val transactionRules = rules.filter { it.action.subCategory == "transaction" }
        val (safetyNet, specific) = transactionRules.partition { it.id.startsWith("generic-") || it.id.startsWith("atm-") }
        assertThat(safetyNet.map { it.id }).containsAtLeast("generic-debit-05", "generic-credit-03")
        assertThat(safetyNet.maxOf { it.priority }).isLessThan(specific.minOf { it.priority })

        // Behavioural proof on the very body both match: the BOB rule wins
        // from a BOB sender, the generic rule only picks up strangers.
        val generic = rules.first { it.id == "generic-debit-05" }
        assertThat(engine.evaluate(listOf(generic), "VM-BOBSMS", bobUpiDebit, anchor)).isNotNull()
        assertThat(evaluate("VM-BOBSMS", bobUpiDebit)?.matchedRuleId).isEqualTo("bob-upi-debit-01")
        assertThat(evaluate("AX-UNKBNK-S", bobUpiDebit)?.matchedRuleId).isEqualTo("generic-debit-05")
    }

    @Test
    fun `doctor and crore near-misses never become transactions`() {
        // "Dr." right after an amount, but a doctor's name, not an account.
        val doctor =
            categorize(
                "VM-HOSPTL-S",
                "Appointment confirmed. Consultation fee Rs.500.00 Dr. Mehta from Apollo Clinic, 10:30 on 12-11-26. Token 14",
            )
        assertThat(doctor.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
        assertThat(doctor.matchedRuleId).isNotEqualTo("generic-debit-05")
        val crore = categorize("AX-REALTY-S", "Flats from Rs 1.2 Cr to your account manager call now 1800000000")
        assertThat(crore.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
        // "Cr. to <vpa>" inside the DEBIT body must not read as a credit.
        assertThat(evaluate("AX-UNKBNK-S", bobUpiDebit)?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `the colon-separated avlbal timestamp is not a date the parser can read`() {
        // Deliberately NOT extracted by the rules: the typed date parser knows
        // d-m-yy, d/m/yyyy, d-MMM-yy and ISO, not "2026:11:11"; and a
        // transaction row dates from the SMS timestamp anyway.
        assertThat(ReminderParser().parseDate("2026:11:11 10:28:46", anchor)).isNull()
    }

    @Test
    fun `a one-step always-sort-as rule sets a category and extracts nothing`() {
        // What the reporter tried: it cannot create a transaction, because
        // transactionFromExtracts needs amount AND type extracts.
        val rule = SenderRule.definition("VM-BOBSMS", "important")
        assertThat(rule.action.extract).isEmpty()
        assertThat(rule.action.subCategory).isNull()
        val result = engine.evaluate(listOf(rule), "VM-BOBSMS", bobUpiDebit, anchor)
        assertThat(result?.subCategory).isNull()
        assertThat(result?.extracted).isEmpty()
    }

    @Test
    fun `mspl real-estate rule needs a whole word not any cr substring`() {
        val microsoft = evaluate("MSPLBN", "Your Microsoft 365 subscription renews on 12-11-26. Secret code: none")
        assertThat(microsoft?.matchedRuleId).isNotEqualTo("mspl-realestate-01")
        val crore = evaluate("MSPLBN", "Luxury 3 BHK homes from Rs 1.2 Cr. Site visit this weekend!")
        assertThat(crore?.matchedRuleId).isEqualTo("mspl-realestate-01")
    }
}
