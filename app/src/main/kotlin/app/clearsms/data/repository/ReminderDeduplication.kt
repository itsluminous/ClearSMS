package app.clearsms.data.repository

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Collapses duplicate reminders that describe the same bill.
 *
 * Two SMS about the same credit-card bill (a statement plus a later
 * follow-up) each produce a reminder row; showing both is a bug. This
 * de-duplicates at the read layer - no schema change.
 *
 * Logical identity of a bill:
 * - the account/card last-4 digits (falling back to the bank name when the
 *   last-4 is missing; a reminder with neither is never merged),
 * - the [ReminderEntity.type],
 * - the due date normalized to a calendar day (a missing due date is its own
 *   distinct bucket).
 *
 * Different cards, different months or different reminder types therefore
 * always stay separate.
 *
 * Merge rule (newest wins, non-null fallback): within a group the reminder
 * whose source message is most recent ([ReminderEntity.createdAt]) is kept -
 * a later SMS reflects the current state of the bill. For [ReminderEntity.totalDue]
 * and [ReminderEntity.minDue] the newest non-null value wins: when the newest
 * reminder lacks a field, the value is taken from the most recent older
 * reminder that has it. The kept row also keeps the newest [ReminderEntity.rawSmsId],
 * so click-through opens the latest source message.
 */
object ReminderDeduplication {
    fun dedupe(
        reminders: List<ReminderEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderEntity> {
        val groups = LinkedHashMap<Any, MutableList<ReminderEntity>>()
        for (reminder in reminders) {
            groups.getOrPut(identityOf(reminder, zone)) { mutableListOf() }.add(reminder)
        }
        return groups.values.map(::merge)
    }

    /**
     * Logical identity key of [reminder] - rows sharing a key describe the
     * same bill/delivery. Also used by the write path so dismiss / restore /
     * delete-forever act on the WHOLE duplicate group (flagging only the
     * displayed row would let a hidden duplicate resurrect the card).
     */
    internal fun identityOf(
        reminder: ReminderEntity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Any {
        if (reminder.type == ReminderType.DELIVERY) {
            // Same order/tracking reference = same delivery, regardless of
            // date: a later SMS with a revised date REPLACES the old card
            // (the common "delayed delivery" case).
            reminder.label?.takeIf { it.isNotBlank() }?.let { return "delivery-ref-" + it.uppercase() }
            // No reference: group by courier/merchant + expected day.
            val merchant =
                reminder.bankName?.takeIf { it.isNotBlank() }?.uppercase()
                    ?: return "row-${reminder.id}-${reminder.rawSmsId}"
            return Triple("delivery-$merchant", reminder.type, reminder.dueDate?.let { dueDay(it, zone) })
        }
        val account =
            reminder.accountLast4?.takeIf { it.isNotBlank() }
                ?: reminder.bankName?.takeIf { it.isNotBlank() }?.uppercase()
                // No identity at all: key on the row itself so it is never merged.
                ?: return "row-${reminder.id}-${reminder.rawSmsId}"
        return Triple(account, reminder.type, reminder.dueDate?.let { dueDay(it, zone) })
    }

    private fun dueDay(
        dueMs: Long,
        zone: ZoneId,
    ): LocalDate = Instant.ofEpochMilli(dueMs).atZone(zone).toLocalDate()

    /** Newest reminder wins; null amount/label fields fall back to the most recent non-null value. */
    private fun merge(group: List<ReminderEntity>): ReminderEntity {
        if (group.size == 1) return group.first()
        val newestFirst = group.sortedByDescending { it.createdAt }
        val newest = newestFirst.first()
        return newest.copy(
            totalDue = newestFirst.firstNotNullOfOrNull { it.totalDue },
            minDue = newestFirst.firstNotNullOfOrNull { it.minDue },
            label = newestFirst.firstNotNullOfOrNull { it.label },
            // Dismissal is sticky across the group: a re-parsed duplicate
            // (same bill, new row) must NOT resurrect an actively-dismissed
            // alert - the merged card stays dismissed until restored.
            dismissedAt = group.mapNotNull { it.dismissedAt }.maxOrNull(),
        )
    }
}
