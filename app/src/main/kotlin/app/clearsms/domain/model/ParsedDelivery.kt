package app.clearsms.domain.model

import java.time.LocalDate

/**
 * A delivery expectation extracted from a courier / e-commerce SMS.
 *
 * Exactly one of [explicitDate] / [relativeDays] is set. Relative phrases
 * ("arriving today", "out for delivery") are resolved against the DATE OF
 * THE MESSAGE - not the current clock - via [expectedDate], so importing an
 * old message never produces a wrong future date.
 */
data class ParsedDelivery(
    /** Explicit expected date parsed from the body, if any. */
    val explicitDate: LocalDate? = null,
    /** Days after the message date ("today"/"out for delivery" = 0, "tomorrow" = 1). */
    val relativeDays: Long? = null,
    /** Courier or merchant name, when recognizable. */
    val merchant: String? = null,
    /** Order / tracking / AWB reference, when present. */
    val reference: String? = null,
) {
    /** The expected delivery date, resolving relative phrases against [messageDate]. */
    fun expectedDate(messageDate: LocalDate): LocalDate = explicitDate ?: messageDate.plusDays(relativeDays ?: 0L)
}
