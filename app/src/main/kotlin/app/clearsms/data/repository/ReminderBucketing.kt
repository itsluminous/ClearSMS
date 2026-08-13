package app.clearsms.data.repository

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import java.util.concurrent.TimeUnit

/**
 * The Alerts age policy: which reminders are ACTIVE (the top list) and which
 * belong to the collapsed "Older alerts" section.
 *
 * A reminder is active only while it is still actionable:
 * - Dated entries (bills, deliveries, travel) are governed by their date -
 *   due today or later is active, past the date they move to Older.
 * - An undated DELIVERY (a dispatch notice with no stated arrival) is only
 *   relevant while the parcel is plausibly in transit: [UNDATED_DELIVERY_ACTIVE_DAYS]
 *   (14) days from the MESSAGE date. Domestic parcels arrive within about
 *   two weeks; past that the dispatch alert is stale, and - crucially - a
 *   years-old dispatch surfaced by a re-sort or catch-up import lands
 *   straight in Older instead of masquerading as an upcoming delivery.
 * - An undated bill-style reminder (e.g. a BESCOM "bill generated" text
 *   with no due date) is actionable for [UNDATED_BILL_ACTIVE_DAYS] (30)
 *   days from the message date: bills recur monthly, so the next cycle's
 *   SMS supersedes it within a month (30 days also matches the recycle
 *   bin's retention convention).
 * - A dismissed reminder ([ReminderEntity.dismissedAt] set) is never
 *   active, whatever its date.
 *
 * Older is the COMPLETE archive: rows are never auto-purged (the 0.10.5
 * 90-day retention sweep silently erased history the user wanted to check).
 * Leaving Older is always an explicit user act - per-card delete-forever or
 * the bulk "clear older" affordance.
 *
 * Ordering interleaves dated and undated rows on ONE axis: a row's
 * effective date is its real due date, or - when it has none - the message
 * date plus [ASSUMED_DUE_OFFSET_DAYS] (15, the midpoint of the 14/30-day
 * undated activity windows). The assumed date is a SORT KEY ONLY: it is
 * never stored on the entity and never rendered (cards show a date line
 * only for a real [ReminderEntity.dueDate]).
 */
object ReminderBucketing {
    const val UNDATED_DELIVERY_ACTIVE_DAYS: Long = 14
    const val UNDATED_BILL_ACTIVE_DAYS: Long = 30

    /** Days after the message date an UNDATED row is assumed due, for sorting only. */
    const val ASSUMED_DUE_OFFSET_DAYS: Long = 15

    data class Buckets(
        /** Actionable alerts, soonest effective date first. */
        val active: List<ReminderEntity>,
        /** Dismissed + expired alerts - the full archive, newest effective date first. */
        val older: List<ReminderEntity>,
    )

    /**
     * Partitions [reminders] (typically the whole table) into active/Older.
     * Deduplication runs FIRST over all rows so a re-parsed duplicate of a
     * dismissed bill merges into the dismissed identity instead of
     * resurrecting as a fresh active alert - see [ReminderDeduplication].
     *
     * [nowMs] is the upcoming/past cutoff (start of today, so an item due
     * today still counts as active).
     */
    fun bucket(
        reminders: List<ReminderEntity>,
        nowMs: Long,
    ): Buckets {
        val deduped = ReminderDeduplication.dedupe(reminders)
        val (active, older) = deduped.partition { it.dismissedAt == null && isActive(it, nowMs) }
        return Buckets(
            active = active.sortedBy(::effectiveDate),
            older = older.sortedByDescending(::effectiveDate),
        )
    }

    /**
     * The single sort axis for both lists: the real due date, or for undated
     * rows the message date plus [ASSUMED_DUE_OFFSET_DAYS]. Never displayed.
     */
    fun effectiveDate(reminder: ReminderEntity): Long =
        reminder.dueDate ?: (reminder.createdAt + TimeUnit.DAYS.toMillis(ASSUMED_DUE_OFFSET_DAYS))

    /** Whether a non-dismissed [reminder] is still inside its active window at [nowMs]. */
    fun isActive(
        reminder: ReminderEntity,
        nowMs: Long,
    ): Boolean {
        reminder.dueDate?.let { return it >= nowMs }
        return reminder.createdAt + TimeUnit.DAYS.toMillis(undatedActiveDays(reminder.type)) > nowMs
    }

    /** Days an UNDATED reminder of [type] stays active, counted from the message date. */
    fun undatedActiveDays(type: ReminderType): Long =
        if (type == ReminderType.DELIVERY) UNDATED_DELIVERY_ACTIVE_DAYS else UNDATED_BILL_ACTIVE_DAYS
}
