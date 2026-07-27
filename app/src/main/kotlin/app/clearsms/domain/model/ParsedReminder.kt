package app.clearsms.domain.model

import java.time.LocalDate

/** Raw fields extracted from a bill / payment reminder SMS. */
data class ParsedReminder(
    val type: ReminderType,
    val dueDate: LocalDate? = null,
    val totalDue: Double? = null,
    val minDue: Double? = null,
    val accountLast4: String? = null,
    val bankName: String? = null,
)
