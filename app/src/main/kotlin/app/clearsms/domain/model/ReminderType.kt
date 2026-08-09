package app.clearsms.domain.model

/** Kind of payment / delivery reminder extracted from an SMS. */
enum class ReminderType {
    CREDIT_CARD,
    EMI,

    /** Recurring/fixed deposit contribution - money saved, not a loan EMI. */
    DEPOSIT,
    INSURANCE,
    SUBSCRIPTION,
    DELIVERY,
    OTHER,
}
