package app.clearsms.domain.model

/** An OTP code extracted from a message body. */
data class ParsedOtp(
    val code: String,
)
