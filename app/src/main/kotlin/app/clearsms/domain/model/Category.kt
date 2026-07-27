package app.clearsms.domain.model

/**
 * Primary message categories shown as inbox tabs.
 *
 * "Unread" is a filter applied on top of these categories, not a category itself.
 */
enum class Category {
    IMPORTANT,
    PROMOTIONAL,
    PERSONAL,
    UNKNOWN,
    OTP,
}
