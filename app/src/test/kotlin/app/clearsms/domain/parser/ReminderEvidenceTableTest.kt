package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The reminder evidence table: the bundled asset must be byte-identical to
 * the `rules/tables/` master, the data-driven classifier must reproduce the
 * deleted Kotlin evidence constants EXACTLY (fixtures per type plus — when a
 * device corpus is present under /tmp — every real message), scoring and
 * tie-break must be unchanged, and a malformed table must degrade to
 * fallback-only classification without crashing.
 */
class ReminderEvidenceTableTest {
    private val classifier = ReminderTypeClassifier()

    // region rules/ <-> assets identity

    @Test
    fun `reminder evidence master and bundled copy are identical`() {
        val master = repoFile("rules/tables/reminder_evidence.json")
        val bundled = repoFile("app/src/main/assets/tables/reminder_evidence.json")
        assertThat(bundled.readText()).isEqualTo(master.readText())
    }

    // endregion

    // region per-type classification fixtures

    @Test
    fun `credit card evidence with statement support`() {
        assertThat(classify("HDFCBK", "Your credit card statement: total amt due Rs 15,240 by 05-08-26"))
            .isEqualTo(ReminderType.CREDIT_CARD)
    }

    @Test
    fun `deposit evidence beats EMI for an RD instalment (tie-break order)`() {
        // "RD Installment ... EMI" scores 3 for both; DEPOSIT is earlier in
        // the fixed tie-break order because an RD instalment is a deposit
        // contribution, not a loan EMI.
        assertThat(classify("HDFCBK", "RD Installment of Rs 5000 due, pay like an EMI"))
            .isEqualTo(ReminderType.DEPOSIT)
    }

    @Test
    fun `loan EMI classifies as EMI`() {
        assertThat(classify("BANK", "Your loan EMI of Rs 12,000 is due on 05-08-26"))
            .isEqualTo(ReminderType.EMI)
    }

    @Test
    fun `insurance premium obligation with insurer support`() {
        assertThat(classify("LICIND", "Renewal premium of Rs.24,000 for LIC policy no. 12345 is due"))
            .isEqualTo(ReminderType.INSURANCE)
    }

    @Test
    fun `tier premium disqualifier suppresses insurance evidence from data`() {
        // "LIV Premium subscription" must never count as premium evidence —
        // the not_if_guard row in the data table carries the suppression.
        assertThat(classify("LIVCNF", "Your LIV Premium subscription renews on 05-08-26"))
            .isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `known biller sender with a bare bill mention is a bill`() {
        // No bill phrase, no domain word: only the sender-gated composite row
        // (only_if_no_other_evidence) can score this.
        assertThat(classify("AIRBIL", "Your bill of Rs 649 awaits payment"))
            .isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `bill evidence still disqualifies subscription (kotlin arbitration)`() {
        assertThat(
            classify("AIRBIL", "Bill for your Airtel Mobile dated 15-MAY-26 has been generated. Plan renewal included."),
        ).isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `plan plus renewal reach the threshold without the subscription word`() {
        assertThat(classify("OTTAPP", "Your plan will renew on 05-08-26"))
            .isEqualTo(ReminderType.SUBSCRIPTION)
    }

    @Test
    fun `support alone can never reach the threshold`() {
        // An insurer name (support, +2) with no evidence row must not classify.
        assertThat(classify("LICIND", "Greetings from LIC")).isNull()
    }

    @Test
    fun `dated instalment with no stronger context falls back to OTHER`() {
        assertThat(classify("SOCTY", "Your installment is pending"))
            .isEqualTo(ReminderType.OTHER)
    }

    @Test
    fun `nothing scores, nothing classifies`() {
        assertThat(classify("FRIEND", "See you at dinner tomorrow")).isNull()
    }

    // endregion

    // region degradation

    @Test
    fun `malformed table degrades to an empty table`() {
        assertThat(ParserTables.parseReminderEvidence("{ not json").types).isEmpty()
        assertThat(ParserTables.parseReminderEvidence(null).types).isEmpty()
    }

    @Test
    fun `unsafe or invalid rows are skipped without taking down siblings`() {
        val table =
            ParserTables.parseReminderEvidence(
                """
                {"types":[{"type":"EMI","evidence":[
                    {"pattern":"(a+)+b","score":3},
                    {"pattern":"broken(","score":3},
                    {"table_ref":"no_such_table","score":3},
                    {"not_if_guard":"no_such_guard","pattern":"x","score":3},
                    {"score":3},
                    {"pattern":"(?i)\\bEMI\\b","score":3}
                ]}]}
                """.trimIndent(),
            )
        val rows = table.types.getValue("EMI").evidence
        assertThat(rows).hasSize(1)
        assertThat(rows.single().bodyRegex!!.containsMatchIn("Your EMI is due")).isTrue()
    }

    @Test
    fun `every shipped row survives validation and loading`() {
        val master = repoFile("rules/tables/reminder_evidence.json").readText()
        val declaredRows = Regex("\"score\"").findAll(master).count()
        val table = ParserTables.parseReminderEvidence(master)
        val loadedRows = table.types.values.sumOf { it.evidence.size + it.support.size }
        assertThat(loadedRows).isEqualTo(declaredRows)
        assertThat(table.fallback.containsMatchIn("installment")).isTrue()
    }

    // endregion

    // region golden equivalence with the deleted Kotlin constants

    @Test
    fun `data-driven classifier agrees with the frozen constants on the fixture set`() {
        for ((sender, body) in FIXTURES) {
            assertWithMessage("classification diverged for a fixture from '$sender'")
                .that(classifier.classify(sender, body))
                .isEqualTo(frozenClassify(sender, body))
        }
    }

    @Test
    fun `data-driven classifier agrees with the frozen constants on the device corpus`() {
        val corpus = File(CORPUS)
        assumeTrue("no device corpus at $CORPUS; skipping", corpus.isFile)
        var checked = 0
        var classified = 0
        corpus.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            val sender = obj["sender"]?.jsonPrimitive?.content ?: return@forEach
            val body = obj["body"]?.jsonPrimitive?.content ?: return@forEach
            val old = frozenClassify(sender, body)
            val new = classifier.classify(sender, body)
            assertWithMessage("reminder type diverged on a corpus message from a sender like '${sender.take(2)}…'")
                .that(new)
                .isEqualTo(old)
            checked++
            if (old != null) classified++
        }
        assumeTrue("corpus empty", checked > 0)
        // Counts only — never message content.
        File(corpus.parentFile, "refactor345-reminder-golden.txt")
            .writeText("reminder golden: $checked messages, $classified typed, 0 disagreements\n")
    }

    private fun classify(
        sender: String,
        body: String,
    ) = classifier.classify(sender, body)

    // endregion

    /**
     * Verbatim replica of the classifier BEFORE the evidence moved to data:
     * the deleted Kotlin pattern constants plus the (unchanged) scoring,
     * threshold and tie-break. Do not edit.
     */
    private fun frozenClassify(
        sender: String,
        body: String,
    ): ReminderType? {
        fun creditCard(): Int {
            var s = 0
            if (FROZEN_CREDIT_CARD.containsMatchIn(body)) s += 3
            if (s > 0 && FROZEN_CARD_STATEMENT_SUPPORT.containsMatchIn(body)) s += 1
            return s
        }

        fun deposit(): Int = if (FROZEN_DEPOSIT.containsMatchIn(body)) 3 else 0

        fun emi(): Int = if (FROZEN_EMI.containsMatchIn(body)) 3 else 0

        fun insurance(): Int {
            var s = 0
            if (FROZEN_POLICY_NUMBER.containsMatchIn(body)) s += 3
            if (!GuardLibrary.matches(GuardId.TIER_PREMIUM, body) && FROZEN_PREMIUM_OBLIGATION.containsMatchIn(body)) s += 3
            if (FROZEN_INSURANCE_WORD.containsMatchIn(body)) s += 3
            if (s > 0 && ParserTables.billers.insurerNameRegex.containsMatchIn(body)) s += 2
            return s
        }

        fun bill(): Int {
            var s = 0
            if (FROZEN_BILL_PHRASE.containsMatchIn(body)) s += 3
            // Round T: an upcoming autopay/mandate/standing-instruction debit
            // is bill evidence — future tense means an obligation, not a move.
            if (FROZEN_UPCOMING_DEBIT.containsMatchIn(body)) s += 3
            if (ParserTables.billers.billDomainRegex.containsMatchIn(body)) s += 3
            val knownBiller = ParserTables.billers.knownBillerSenderRegex.containsMatchIn(sender)
            if (s == 0 && knownBiller && FROZEN_BILL_WORD.containsMatchIn(body)) s += 3
            if (s > 0 && knownBiller) s += 1
            return s
        }

        fun subscription(): Int {
            var s = 0
            if (FROZEN_SUBSCRIPTION_WORD.containsMatchIn(body)) s += 3
            if (FROZEN_PLAN_WORD.containsMatchIn(body)) s += 2
            if (FROZEN_RENEWAL_WORD.containsMatchIn(body)) s += 2
            return s
        }

        val billScore = bill()
        val scores =
            listOf(
                ReminderType.CREDIT_CARD to creditCard(),
                ReminderType.DEPOSIT to deposit(),
                ReminderType.EMI to emi(),
                ReminderType.INSURANCE to insurance(),
                ReminderType.OTHER to billScore,
                ReminderType.SUBSCRIPTION to if (billScore >= 3) 0 else subscription(),
            )
        val best = scores.maxByOrNull { it.second }!!
        if (best.second >= 3) return best.first
        if (FROZEN_INSTALLMENT.containsMatchIn(body)) return ReminderType.OTHER
        return null
    }

    private fun repoFile(repoRelativePath: String): File =
        sequenceOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File(repoRelativePath.removePrefix("app/")),
        ).first(File::exists)

    private companion object {
        /** Optional local corpus (JSONL of {"sender","body"}), never committed. */
        const val CORPUS = "/tmp/clearsms/guard-corpus.jsonl"

        val FROZEN_CREDIT_CARD =
            Regex("(?i)credit\\s*card|card\\s+(?:bill|statement|ending)|(?:mini\\s+)?statement\\s+for\\s+(?:your\\s+)?card\\b")
        val FROZEN_CARD_STATEMENT_SUPPORT =
            Regex("(?i)\\b(?:e-?)?statement\\b|total\\s+(?:amt|amount)?\\s*due|min(?:imum)?\\s+(?:amt|amount)?\\s*due|cardmember")
        val FROZEN_DEPOSIT =
            Regex(
                "(?i)recurring\\s+deposit|fixed\\s+deposit|" +
                    "\\bRD\\b[^\\n]{0,60}?instal?l?ment|instal?l?ment[^\\n]{0,60}?\\bRD\\b|" +
                    "\\bSIP\\b[^\\n]{0,60}?(?:due|instal)",
            )
        val FROZEN_EMI = Regex("(?i)\\bEMI\\b|loan\\s+instal?lment")
        val FROZEN_INSTALLMENT = Regex("(?i)instal?lment")
        val FROZEN_POLICY_NUMBER = Regex("(?i)\\bpolicy\\s*(?:no\\.?|number)\\b")
        val FROZEN_PREMIUM_OBLIGATION =
            Regex(
                "(?i)premium\\s+(?:due|amount)\\b|" +
                    "premium\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)|" +
                    "renewal\\s+premium|" +
                    "premium\\s+(?:will\\s+be\\s+|shall\\s+be\\s+)?(?:deducted|charged)|" +
                    "auto\\s*[- ]?debit\\s+premium",
            )
        val FROZEN_INSURANCE_WORD = Regex("(?i)\\binsurance\\b|\\binsurer\\b|life\\s+cover\\b|\\bpolicy\\s+premium\\b")
        val FROZEN_BILL_PHRASE =
            Regex(
                "(?i)\\bbill\\s+(?:for|dated|of|no\\.?)\\b|" +
                    "bill\\s+(?:has\\s+been\\s+)?generated|" +
                    "amount\\s+to\\s+be\\s+paid|\\bbill\\s+amount\\b|" +
                    "payment\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,.]+\\s+is\\s+due\\b[^\\n]{0,60}?\\bfor\\s+your\\b",
            )
        val FROZEN_BILL_WORD = Regex("(?i)\\bbill\\b")
        val FROZEN_UPCOMING_DEBIT =
            Regex(
                "(?i)\\bwill\\s+be\\s+debited\\b[^\\n]{0,80}?\\b(?:towards|for|from)\\b|" +
                    "\\bautopay\\b|\\bupi\\s+mandate\\b|\\bstanding\\s+instruction",
            )
        val FROZEN_SUBSCRIPTION_WORD = Regex("(?i)\\bsubscription\\b|\\bmembership\\b|auto[-\\s]?renew")
        val FROZEN_PLAN_WORD = Regex("(?i)\\bplan\\b")
        val FROZEN_RENEWAL_WORD = Regex("(?i)\\brenew(?:al|s|ed)?\\b")

        /** One fixture per evidence shape, plus near-miss negatives. */
        val FIXTURES =
            listOf(
                "HDFCBK" to "Your credit card statement: total amt due Rs 15,240 min amt due Rs 760 by 05-08-26",
                "HDFCBK" to "Mini statement for your card ending 1234",
                "HDFCBK" to "RD Installment of Rs 5,000 due for A/c XX6894",
                "SBIBNK" to "Your SIP of Rs 2000 is due on 05-08-26",
                "BAJAJF" to "Your loan EMI of Rs 12,000 is due on 05-08-26",
                "LICIND" to "Renewal premium of Rs.24,000 for policy no. H1234567 is due",
                "LICIND" to "premium of Rs 5000 is due on 05-May-26",
                "LIVCNF" to "Yay! Your LIV Premium subscription is now active, valid till 15 Oct 2026",
                "OTTAPP" to "YouTube Premium plan renews on 05-08-26",
                "AIRBIL" to "Bill for your Airtel Mobile dated 15-MAY-26 has been generated. Amount to be paid: Rs 649",
                "AIRBIL" to "Your bill of Rs 649 awaits payment",
                "BESCOM" to "Electricity bill Rs 1,240 due 10-08-26",
                "NOBODY" to "Your bill is here",
                "OTTAPP" to "Your plan will renew on 05-08-26",
                "SOCTY" to "Maintenance fee installment pending",
                "LICIND" to "Greetings from LIC, wish you a great year",
                "FRIEND" to "See you at dinner tomorrow",
                "HDFCBK" to "Payment of INR 532.62 for your Axis Bank Credit Card is due on 04-04-26",
                "INSURE" to "Your insurance policy premium will be deducted via auto debit premium",
                "JIOBB" to "Your broadband plan expires today, renew now",
            )
    }
}
