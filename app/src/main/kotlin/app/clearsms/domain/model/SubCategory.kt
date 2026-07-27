package app.clearsms.domain.model

/** Fine-grained tags applied to messages within a primary [Category]. */
enum class SubCategory {
    TRANSACTION,
    OTP,
    BILL,
    BANK_ALERT,
    GOVERNMENT,
    RECHARGE,
    INVESTMENT,
    DELIVERY,
    OFFER,
    SCAM,
    FIXED_DEPOSIT,
    MUTUAL_FUND,
    GENERAL,
}
