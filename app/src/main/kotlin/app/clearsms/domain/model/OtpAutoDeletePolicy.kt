package app.clearsms.domain.model

/** How long OTP messages are kept before automatic deletion. */
enum class OtpAutoDeletePolicy {
    NEVER,
    HOURS_24,
    DAYS_3,
    DAYS_7,
    MONTH_1,
    MONTHS_3,
}
