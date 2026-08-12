package app.clearsms.domain.model

import java.time.LocalDate

/**
 * A delivery expectation extracted from a courier / e-commerce SMS.
 *
 * At most one of [explicitDate] / [relativeDays] is set. Relative phrases
 * ("arriving today", "out for delivery") are resolved against the DATE OF
 * THE MESSAGE - not the current clock - via [expectedDate], so importing an
 * old message never produces a wrong future date. A dispatch notice ("is
 * dispatched via <courier> AWB <id>") states no arrival at all: both fields
 * are null and [expectedDate] returns null - an undated, dismissible
 * "on the way" Alerts card, never a fabricated ETA.
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
    /**
     * The expected delivery date, resolving relative phrases against
     * [messageDate]; null for a dispatch notice that states no arrival.
     */
    fun expectedDate(messageDate: LocalDate): LocalDate? = explicitDate ?: relativeDays?.let { messageDate.plusDays(it) }
}
