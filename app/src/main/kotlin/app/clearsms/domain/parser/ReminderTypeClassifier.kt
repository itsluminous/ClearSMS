package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType

/**
 * Evidence-scored reminder type classification.
 *
 * The previous implementation was a fixed-precedence keyword chain where a
 * single loose word decided the type ("premium" -> INSURANCE, "plan" ->
 * SUBSCRIPTION). That misfiled OTT product tiers ("LIV Premium subscription")
 * as insurance and telecom bills that happened to mention a plan as
 * subscriptions - and split identical bills from one biller across two types
 * depending on which stray keyword appeared.
 *
 * Instead, every type accumulates a score from ANCHORED evidence and the
 * highest score wins. Classification is a pure function of (sender, body), so
 * the same message always yields the same type.
 *
 * The EVIDENCE - the per-type patterns, weights, corroborating support rows
 * and disqualifiers (e.g. product-tier "Premium" never counting as an
 * insurance premium, via the `tier_premium` guard - see
 * [GuardId.TIER_PREMIUM]) - is community-editable data:
 * `rules/tables/reminder_evidence.json`, loaded through
 * [ParserTables.reminderEvidence] with ReDoS validation, and documented
 * inline in that file. What each type requires lives THERE now, not in this
 * KDoc.
 *
 * The SCORING stays here, in code:
 * - a type must reach [SCORE_THRESHOLD] to be eligible;
 * - support rows are added only when at least one evidence row matched, so
 *   corroboration alone can never reach the threshold;
 * - ties break in a fixed, documented order - most specific first:
 *   CREDIT_CARD, DEPOSIT (an "RD instalment" is a deposit contribution, not
 *   a loan EMI), EMI, INSURANCE, BILL (as [ReminderType.OTHER]),
 *   SUBSCRIPTION;
 * - bill evidence disqualifies SUBSCRIPTION outright: telecom / broadband
 *   bills routinely mention the tariff "plan" and "renewal", but a
 *   generated bill is a bill;
 * - when nothing reaches the threshold, a dated instalment with no stronger
 *   context (the table's fallback pattern) is still a payment obligation -
 *   kept, but only as the generic type.
 *
 * When adding a rule for a new message shape, add ANCHORED evidence (keyword
 * plus its obligating context) to the data table, never a bare keyword -
 * bare keywords are what caused the misclassification this replaces.
 */
class ReminderTypeClassifier {
    /** Best-evidence type for [body], or null when nothing scores. */
    fun classify(
        sender: String,
        body: String,
    ): ReminderType? {
        val table = ParserTables.reminderEvidence
        val billScore = score(table, TYPE_BILL, sender, body)
        val scores =
            listOf(
                // Tie-break order is the order of this list - most specific first.
                ReminderType.CREDIT_CARD to score(table, "CREDIT_CARD", sender, body),
                ReminderType.DEPOSIT to score(table, "DEPOSIT", sender, body),
                ReminderType.EMI to score(table, "EMI", sender, body),
                ReminderType.INSURANCE to score(table, "INSURANCE", sender, body),
                ReminderType.OTHER to billScore,
                // Bill evidence disqualifies subscription outright: telecom /
                // broadband bills routinely mention the tariff "plan" and
                // "renewal", but a generated bill is a bill.
                ReminderType.SUBSCRIPTION to
                    if (billScore >= SCORE_THRESHOLD) 0 else score(table, "SUBSCRIPTION", sender, body),
            )
        val best = scores.maxByOrNull { it.second }!!
        if (best.second >= SCORE_THRESHOLD) return best.first
        // A dated instalment with no stronger context is still a payment
        // obligation - keep it, but only as the generic type.
        if (table.fallback.containsMatchIn(body)) return ReminderType.OTHER
        return null
    }

    /**
     * Sums a type's evidence rows in declaration order (rows flagged
     * `only_if_no_other_evidence` count only when nothing matched yet), then
     * adds support rows - but only when some evidence matched at all.
     */
    private fun score(
        table: ReminderEvidenceTable,
        type: String,
        sender: String,
        body: String,
    ): Int {
        val rows = table.types[type] ?: return 0
        var evidence = 0
        for (row in rows.evidence) {
            if (row.onlyIfNoOtherEvidence && evidence > 0) continue
            if (row.matches(sender, body)) evidence += row.score
        }
        if (evidence == 0) return 0
        var total = evidence
        for (row in rows.support) {
            if (row.matches(sender, body)) total += row.score
        }
        return total
    }

    private companion object {
        /** Minimum score a type needs to be eligible at all. */
        const val SCORE_THRESHOLD = 3

        /** Bill evidence classifies as [ReminderType.OTHER]. */
        const val TYPE_BILL = "BILL"
    }
}
