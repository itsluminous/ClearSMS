package app.clearsms.ui.conversation

/**
 * Visibility rule for the ALWAYS-visible Copy OTP button under an OTP
 * bubble (the in-app twin of the OTP notification's Copy action). Pure so
 * the decision is unit-testable without a Compose harness.
 *
 * Visible only when the message actually carries an extracted OTP - every
 * other message keeps a clean bubble. Hidden while selection mode is
 * active: a live tap target on a bubble during multi-select is a trap
 * (a copy where the user meant to toggle selection), and the selection
 * bar already offers Copy OTP for a single selected OTP message.
 */
object OtpCopyAffordance {
    fun visible(
        extractedOtp: String?,
        selectionActive: Boolean,
    ): Boolean = !extractedOtp.isNullOrBlank() && !selectionActive
}
