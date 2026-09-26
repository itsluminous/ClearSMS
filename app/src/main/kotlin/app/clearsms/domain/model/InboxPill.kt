package app.clearsms.domain.model

/**
 * The filter pills of the Inbox, in default display order.
 *
 * Five pills are the [Category] pills - selecting one shows exactly the
 * threads whose latest message carries that category. [SCAM] is different:
 * it is NOT a sixth category. The app has no spam category and no rules that
 * produce one; what it has is scam FLAGGING - [SubCategory.SCAM] set by the
 * heuristic detector or a `scam` rule on messages that otherwise keep their
 * primary category (heuristic hits land in [Category.PROMOTIONAL]). The
 * "Spam" pill therefore filters the scam-flagged set across categories.
 * Introducing a real category instead would mean new rules, a full re-sort
 * and a stored-data migration for a signal the app already carries.
 *
 * Stored preferences (order, hidden set, labels) persist these enum NAMES,
 * so renaming an entry is a data migration - add, never rename.
 */
enum class InboxPill(
    /** The category this pill filters, or null for the scam-flag pill. */
    val category: Category?,
) {
    IMPORTANT(Category.IMPORTANT),
    PROMOTIONAL(Category.PROMOTIONAL),
    PERSONAL(Category.PERSONAL),
    UNKNOWN(Category.UNKNOWN),
    OTP(Category.OTP),
    SCAM(null),
    ;

    companion object {
        /** The pill that filters [category]; every category has exactly one. */
        fun of(category: Category): InboxPill = entries.first { it.category == category }
    }
}
