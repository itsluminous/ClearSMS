package app.clearsms.domain.model

/** Kind of payment reminder extracted from an SMS. */
enum class ReminderType {
    CREDIT_CARD,
    EMI,
    INSURANCE,
    SUBSCRIPTION,
    OTHER,
}
