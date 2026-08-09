package app.clearsms.ui.alerts

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType

/**
 * Reminder filter pills shown on the Alerts tab, in display order.
 *
 * Every [ReminderType] maps to exactly one pill. [BILL] covers
 * [ReminderType.OTHER]: the parser only ever emits OTHER through bill gates
 * (a recognized bill domain in the body, a known biller sender, a generic
 * instalment, or a bundled bill-reminder rule), so OTHER reminders are
 * bills by construction - the card badge already labels them "Bill". A
 * residual "Others" pill would therefore always be empty and is omitted
 * rather than split by an arbitrary label heuristic.
 */
enum class AlertFilter {
    ALL,
    CREDIT_CARDS,
    EMI,
    INSURANCE,
    BILL,
    SUBSCRIPTION,
    DEPOSIT,
    DELIVERY,
    ;

    /** Whether a reminder of [type] belongs under this pill. */
    fun matches(type: ReminderType): Boolean =
        when (this) {
            ALL -> true
            CREDIT_CARDS -> type == ReminderType.CREDIT_CARD
            EMI -> type == ReminderType.EMI
            INSURANCE -> type == ReminderType.INSURANCE
            BILL -> type == ReminderType.OTHER
            SUBSCRIPTION -> type == ReminderType.SUBSCRIPTION
            DEPOSIT -> type == ReminderType.DEPOSIT
            DELIVERY -> type == ReminderType.DELIVERY
        }

    companion object {
        /**
         * Reminders (upcoming and past combined) per pill, for the count
         * badges. Like the Finance pills, every pill stays visible and the
         * badge is simply hidden at zero.
         */
        fun counts(
            upcoming: List<ReminderEntity>,
            past: List<ReminderEntity>,
        ): Map<AlertFilter, Int> {
            val all = upcoming + past
            return entries.associateWith { filter -> all.count { filter.matches(it.type) } }
        }
    }
}
