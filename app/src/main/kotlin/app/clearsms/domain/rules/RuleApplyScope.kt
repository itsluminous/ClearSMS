package app.clearsms.domain.rules

/**
 * What a newly saved rule can affect, and therefore how much has to be
 * re-sorted for it to take effect on messages already in the inbox.
 *
 * Adding a rule used to change nothing until the user ran Settings → Sort
 * inbox again, which reads as the feature being broken: the rule is listed,
 * the message stays where it was. Re-sorting everything on each save is the
 * other extreme - minutes of work, on a phone, for one rule about one sender.
 */
sealed interface RuleApplyScope {
    /**
     * The rule is bound to a sender, so only that sender's messages can match.
     * [senderCore] is the TRAI-stripped core the rule's sender pattern carries
     * ("HDFCBK" for "VM-HDFCBK"), which is exactly the set to re-sort.
     */
    data class Sender(
        val senderCore: String,
    ) : RuleApplyScope

    /**
     * The rule matches on the body alone (or on a hand-edited sender pattern),
     * so any message in the inbox could match it. Too expensive to apply
     * eagerly: the user is told to run the full re-sort instead.
     */
    data object Everything : RuleApplyScope
}

object RuleScopeResolver {
    /** A pattern that is nothing but the case-insensitive flag and a literal. */
    private val literalSenderPattern = Regex("""^\(\?i\)[A-Za-z0-9 ]+$""")

    /**
     * Decides the scope of a rule saved from the wizard.
     *
     * Narrow only when all three hold:
     * - the rule is bound to the sender (an unbound rule matches everyone);
     * - the sender pattern is the literal the wizard derived, not a hand-edited
     *   regex - an edited pattern like `(?i).*BANK` deliberately reaches other
     *   senders, and quietly re-sorting one of them would under-apply it;
     * - a source sender exists, which rules out editing an existing rule with no
     *   message behind it.
     */
    fun resolve(
        senderPattern: String,
        sourceSender: String,
        boundToSender: Boolean,
        senderPatternEdited: Boolean,
    ): RuleApplyScope {
        if (!boundToSender || senderPatternEdited) return RuleApplyScope.Everything
        if (sourceSender.isBlank() || senderPattern.isBlank()) return RuleApplyScope.Everything
        if (!literalSenderPattern.matches(senderPattern)) return RuleApplyScope.Everything
        val core = senderPattern.removePrefix("(?i)").trim()
        return if (core.isEmpty()) RuleApplyScope.Everything else RuleApplyScope.Sender(core)
    }
}
