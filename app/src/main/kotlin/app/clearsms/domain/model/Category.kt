package app.clearsms.domain.model

/**
 * Primary message categories shown as inbox pills.
 *
 * "Unread" is a filter applied on top of these categories, not a category itself.
 *
 * Notices that require no action and move no money (broker/exchange statements,
 * flight and train PNR info, appointment tokens, credit-score access notices,
 * UPI-mandate lifecycle messages) are classified as [IMPORTANT] with their own
 * sub-category — there is deliberately no separate "Informational" pill.
 */
enum class Category {
    IMPORTANT,
    PROMOTIONAL,
    PERSONAL,
    UNKNOWN,
    OTP,
}
