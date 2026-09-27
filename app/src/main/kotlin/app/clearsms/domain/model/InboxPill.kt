package app.clearsms.domain.model

/**
 * The filter pills of the Inbox, in default display order - one per
 * [Category]. Selecting a pill shows exactly the threads whose latest
 * message carries that category; the Spam pill is [Category.SPAM] and
 * nothing else. Scam FLAGGING ([SubCategory.SCAM]) is deliberately not a
 * pill: it is a warning that rides on top of any category, so a scam-flagged
 * bank alert stays under Important with its warning treatment intact.
 *
 * Stored preferences (order, hidden set, labels) persist these enum NAMES,
 * so renaming an entry is a data migration - add, never rename. (The
 * unreleased scam-flag pill `SCAM` that briefly existed on the development
 * branch decodes leniently: a stored `SCAM` name is dropped and [SPAM] is
 * appended, like any unknown name.)
 */
enum class InboxPill(
    /** The category this pill filters. */
    val category: Category,
) {
    IMPORTANT(Category.IMPORTANT),
    PROMOTIONAL(Category.PROMOTIONAL),
    PERSONAL(Category.PERSONAL),
    UNKNOWN(Category.UNKNOWN),
    OTP(Category.OTP),
    SPAM(Category.SPAM),
    ;

    companion object {
        /** The pill that filters [category]; every category has exactly one. */
        fun of(category: Category): InboxPill = entries.first { it.category == category }
    }
}
