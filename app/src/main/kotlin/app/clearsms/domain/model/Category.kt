package app.clearsms.domain.model

/**
 * Primary message categories shown as inbox tabs.
 *
 * "Unread" is a filter applied on top of these categories, not a category itself.
 */
enum class Category {
    IMPORTANT,
    PROMOTIONAL,

    /**
     * Notices worth keeping but requiring no action and moving no money:
     * broker/exchange balance statements, flight/train PNR and boarding info,
     * appointment tokens, credit-score access notices, service updates,
     * UPI-mandate lifecycle notices. Anything with money actually moved
     * belongs in [IMPORTANT]; anything selling belongs in [PROMOTIONAL].
     */
    INFORMATIONAL,
    PERSONAL,
    UNKNOWN,
    OTP,
}
