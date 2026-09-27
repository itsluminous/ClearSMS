package app.clearsms.domain.model

/**
 * Primary message categories shown as inbox pills.
 *
 * "Unread" is a filter applied on top of these categories, not a category itself.
 *
 * Notices that require no action and move no money (broker/exchange statements,
 * flight and train PNR info, appointment tokens, credit-score access notices,
 * UPI-mandate lifecycle messages) are classified as [IMPORTANT] with their own
 * sub-category - there is deliberately no separate "Informational" pill.
 *
 * [SPAM] is the category for junk: unsolicited bait and phishing the rules
 * or the heuristic scam detector recognise, plus whatever the user files
 * there with a rule. It is a SORTING decision and is distinct from
 * [SubCategory.SCAM], the fraud FLAG: a flag rides on top of any category
 * (a scam-flagged bank alert stays IMPORTANT) and is what drives the
 * warning treatment - the security notification and the link gate. Spam
 * can never swallow a verification code or a money movement: the
 * categorizer's invariants lift such messages out of SPAM exactly as they
 * lift them out of PROMOTIONAL.
 *
 * Stored by NAME (see `Converters`); add entries, never rename them.
 */
enum class Category {
    IMPORTANT,
    PROMOTIONAL,
    PERSONAL,
    UNKNOWN,
    OTP,
    SPAM,
}
