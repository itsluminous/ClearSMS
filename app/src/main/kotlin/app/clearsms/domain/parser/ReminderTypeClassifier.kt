package app.clearsms.domain.parser

import app.clearsms.domain.model.ReminderType

/**
 * Evidence-scored reminder type classification.
 *
 * The previous implementation was a fixed-precedence keyword chain where a
 * single loose word decided the type ("premium" -> INSURANCE, "plan" ->
 * SUBSCRIPTION). That misfiled OTT product tiers ("LIV Premium subscription")
 * as insurance and telecom bills that happened to mention a plan as
 * subscriptions — and split identical bills from one biller across two types
 * depending on which stray keyword appeared.
 *
 * Instead, every type now accumulates a score from ANCHORED evidence and the
 * highest score wins. Classification is a pure function of (sender, body), so
 * the same message always yields the same type.
 *
 * | Type        | Evidence (score)                                              | Disqualifiers |
 * |-------------|---------------------------------------------------------------|---------------|
 * | CREDIT_CARD | "credit card" / "card bill|statement|ending" (3); statement / total-due / min-due context (+1) | — |
 * | DEPOSIT     | RD/FD/recurring-deposit instalment, SIP due (3)               | — |
 * | EMI         | "EMI", "loan instalment" (3)                                  | — |
 * | INSURANCE   | "policy no/number" (3); premium OBLIGATION — "premium due/of Rs/amount", "renewal premium", "premium ... deducted/charged" (3); "insurance"/"policy premium" (3); known insurer name (+2) | Product-tier "premium" — "<Brand> Premium subscription/plan/..." — never counts as premium evidence |
 * | BILL (OTHER)| bill phrase — "bill for/dated/of", "bill ... generated", "amount to be paid", "payment of Rs X is due ... for your" (3); bill domain word — electricity/postpaid/broadband/... (3); "bill" from a known biller sender (3); known biller sender (+1) | — |
 * | SUBSCRIPTION| "subscription"/"membership"/"auto-renew" (3); "plan" (+2); "renew/renewal" (+2) | Zeroed entirely when bill evidence is present — a telecom bill mentioning a plan is a bill |
 * | OTHER       | generic "instalment" fallback, only when no type reaches the threshold | — |
 *
 * A type must reach [SCORE_THRESHOLD] to be eligible. Ties break in a fixed,
 * documented order — most specific first: CREDIT_CARD, DEPOSIT (an "RD
 * instalment" is a deposit contribution, not a loan EMI), EMI, INSURANCE,
 * BILL, SUBSCRIPTION.
 *
 * When adding a rule for a new message shape, add ANCHORED evidence (keyword
 * plus its obligating context), never a bare keyword — bare keywords are what
 * caused the misclassification this replaces.
 */
class ReminderTypeClassifier {
    /** Best-evidence type for [body], or null when nothing scores. */
    fun classify(
        sender: String,
        body: String,
    ): ReminderType? {
        val billScore = billScore(sender, body)
        val scores =
            listOf(
                // Tie-break order is the order of this list — most specific first.
                ReminderType.CREDIT_CARD to creditCardScore(body),
                ReminderType.DEPOSIT to depositScore(body),
                ReminderType.EMI to emiScore(body),
                ReminderType.INSURANCE to insuranceScore(body),
                ReminderType.OTHER to billScore,
                // Bill evidence disqualifies subscription outright: telecom /
                // broadband bills routinely mention the tariff "plan" and
                // "renewal", but a generated bill is a bill.
                ReminderType.SUBSCRIPTION to if (billScore >= SCORE_THRESHOLD) 0 else subscriptionScore(body),
            )
        val best = scores.maxByOrNull { it.second }!!
        if (best.second >= SCORE_THRESHOLD) return best.first
        // A dated instalment with no stronger context is still a payment
        // obligation — keep it, but only as the generic type.
        if (INSTALLMENT_REGEX.containsMatchIn(body)) return ReminderType.OTHER
        return null
    }

    private fun creditCardScore(body: String): Int {
        var score = 0
        if (CREDIT_CARD_REGEX.containsMatchIn(body)) score += STRONG
        if (score > 0 && CARD_STATEMENT_SUPPORT_REGEX.containsMatchIn(body)) score += SUPPORT
        return score
    }

    private fun depositScore(body: String): Int = if (DEPOSIT_REGEX.containsMatchIn(body)) STRONG else 0

    private fun emiScore(body: String): Int = if (EMI_REGEX.containsMatchIn(body)) STRONG else 0

    private fun insuranceScore(body: String): Int {
        var score = 0
        if (POLICY_NUMBER_REGEX.containsMatchIn(body)) score += STRONG
        // "premium" only counts in an obligation phrasing, and never when the
        // body uses it as a product-tier name ("LIV Premium subscription",
        // "YouTube Premium plan") — that word alone used to drag every OTT
        // tier-name confirmation into the insurance bucket.
        if (!TIER_PREMIUM_REGEX.containsMatchIn(body) && PREMIUM_OBLIGATION_REGEX.containsMatchIn(body)) score += STRONG
        if (INSURANCE_WORD_REGEX.containsMatchIn(body)) score += STRONG
        if (score > 0 && INSURER_NAME_REGEX.containsMatchIn(body)) score += 2
        return score
    }

    private fun billScore(
        sender: String,
        body: String,
    ): Int {
        var score = 0
        if (BILL_PHRASE_REGEX.containsMatchIn(body)) score += STRONG
        if (BILL_DOMAIN_REGEX.containsMatchIn(body)) score += STRONG
        val knownBiller = KNOWN_BILLER_SENDER_REGEX.containsMatchIn(sender)
        if (score == 0 && knownBiller && BILL_WORD_REGEX.containsMatchIn(body)) score += STRONG
        if (score > 0 && knownBiller) score += SUPPORT
        return score
    }

    private fun subscriptionScore(body: String): Int {
        var score = 0
        if (SUBSCRIPTION_WORD_REGEX.containsMatchIn(body)) score += STRONG
        if (PLAN_WORD_REGEX.containsMatchIn(body)) score += 2
        if (RENEWAL_WORD_REGEX.containsMatchIn(body)) score += 2
        return score
    }

    private companion object {
        /** Minimum score a type needs to be eligible at all. */
        const val SCORE_THRESHOLD = 3

        /** One piece of strong (anchored) evidence. */
        const val STRONG = 3

        /** Supporting context that alone can never reach the threshold. */
        const val SUPPORT = 1

        val CREDIT_CARD_REGEX =
            Regex("(?i)credit\\s*card|card\\s+(?:bill|statement|ending)|(?:mini\\s+)?statement\\s+for\\s+(?:your\\s+)?card\\b")

        /** Statement context corroborating a card mention. */
        val CARD_STATEMENT_SUPPORT_REGEX =
            Regex("(?i)\\b(?:e-?)?statement\\b|total\\s+(?:amt|amount)?\\s*due|min(?:imum)?\\s+(?:amt|amount)?\\s*due|cardmember")

        /** Recurring/fixed deposit contributions ("RD Installment Due!"), SIP dues. */
        val DEPOSIT_REGEX =
            Regex(
                "(?i)recurring\\s+deposit|fixed\\s+deposit|" +
                    "\\bRD\\b[^\\n]{0,60}?instal?l?ment|instal?l?ment[^\\n]{0,60}?\\bRD\\b|" +
                    "\\bSIP\\b[^\\n]{0,60}?(?:due|instal)",
            )

        /** Loan EMIs only — a bare "instalment" is NOT an EMI. */
        val EMI_REGEX = Regex("(?i)\\bEMI\\b|loan\\s+instal?lment")

        val INSTALLMENT_REGEX = Regex("(?i)instal?lment")

        /** "policy no. H1234567", "Policy Number" — a concrete policy reference. */
        val POLICY_NUMBER_REGEX = Regex("(?i)\\bpolicy\\s*(?:no\\.?|number)\\b")

        /**
         * "premium" in an obligation phrasing: due, an amount, a renewal, or a
         * standing-instruction deduction. Bare "premium" never counts.
         */
        val PREMIUM_OBLIGATION_REGEX =
            Regex(
                "(?i)premium\\s+(?:due|amount)\\b|" +
                    "premium\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)|" +
                    "renewal\\s+premium|" +
                    "premium\\s+(?:will\\s+be\\s+|shall\\s+be\\s+)?(?:deducted|charged)|" +
                    "auto\\s*[- ]?debit\\s+premium",
            )

        /** Explicit insurance wording. */
        val INSURANCE_WORD_REGEX = Regex("(?i)\\binsurance\\b|\\binsurer\\b|life\\s+cover\\b|\\bpolicy\\s+premium\\b")

        /**
         * Widely-known insurer brand names (public names only) — corroborating
         * evidence, never sufficient alone.
         */
        val INSURER_NAME_REGEX =
            Regex(
                "(?i)\\bLIC\\b|ICICI\\s*Pru|HDFC\\s+(?:Life|Ergo)|SBI\\s+Life|Max\\s+Life|Bajaj\\s+Allianz|" +
                    "Tata\\s+AIA|\\bABSLI\\b|Aditya\\s+Birla\\s+Sun\\s+Life|Star\\s+Health|Niva\\s+Bupa|" +
                    "New\\s+India\\s+Assurance|ICICI\\s+Lombard|PNB\\s+MetLife|Kotak\\s+Life|Canara\\s+HSBC",
            )

        /**
         * "premium" as a PRODUCT TIER — "<Brand> Premium subscription/plan"
         * ("LIV Premium subscription", "YouTube Premium plan"). Suppresses
         * premium-based insurance evidence.
         */
        val TIER_PREMIUM_REGEX =
            Regex("(?i)\\b[\\w&+.]+\\s+premium\\s+(?:subscription|plan|membership|pack|account|is\\s+now\\s+active)\\b")

        /**
         * A generated / itemized bill. The trailing alternative covers the
         * "a payment of Rs X is due on <date> for your <product>" biller
         * shape; the amount must sit directly before "is due" so credit-card
         * "payment of INR X for <card> is due" statements do not match.
         */
        val BILL_PHRASE_REGEX =
            Regex(
                "(?i)\\bbill\\s+(?:for|dated|of|no\\.?)\\b|" +
                    "bill\\s+(?:has\\s+been\\s+)?generated|" +
                    "amount\\s+to\\s+be\\s+paid|\\bbill\\s+amount\\b|" +
                    "payment\\s+of\\s+(?:INR|Rs\\.?|\\u20b9)\\s*[\\d,.]+\\s+is\\s+due\\b[^\\n]{0,60}?\\bfor\\s+your\\b",
            )

        /** Recognized bill domains (utility / telecom / broadband / DTH / ...). */
        val BILL_DOMAIN_REGEX =
            Regex(
                "(?i)electricity|\\bpower\\s+bill|water\\s+bill|\\bgas\\b|broadband|internet\\s+bill|" +
                    "landline|postpaid|\\bDTH\\b|\\bd2h\\b|\\brent\\b|property\\s+tax|\\btax\\b|" +
                    "maintenance\\s+(?:bill|fee|charge)|\\bfee\\b|\\bfees\\b|utility\\s+bill|municipal",
            )

        /** A literal bill mention — only meaningful from a known biller sender. */
        val BILL_WORD_REGEX = Regex("(?i)\\bbill\\b")

        /**
         * Utility / telecom / broadband biller sender ids whose "your bill"
         * messages are trusted even without a domain keyword in the body.
         */
        val KNOWN_BILLER_SENDER_REGEX =
            Regex(
                "(?i)ACTGRP|ACTFBN|ACTBBN|ACTBBD|ACTCOR|ACTCRP|AIRBIL|AIRTEL|JIOFBR|JIOBB|BSNL|VICARE|" +
                    "BSES|BESCOM|MSEDCL|TNEB|TSSPD|APSPDC|KSEB|PSPCL|UPPCL|WBSEDC|CESC|" +
                    "ADANI|TATAPW|TPDDL|TORRNT|IGL|MGL|MAHGAS|GAIL|HPCL|BPCL|IOCL",
            )

        /** OTT / membership products that renew. */
        val SUBSCRIPTION_WORD_REGEX = Regex("(?i)\\bsubscription\\b|\\bmembership\\b|auto[-\\s]?renew")

        /** Weak: a tariff/product "plan" — supporting only. */
        val PLAN_WORD_REGEX = Regex("(?i)\\bplan\\b")

        /** Weak: renewal wording — supporting only. */
        val RENEWAL_WORD_REGEX = Regex("(?i)\\brenew(?:al|s|ed)?\\b")
    }
}
