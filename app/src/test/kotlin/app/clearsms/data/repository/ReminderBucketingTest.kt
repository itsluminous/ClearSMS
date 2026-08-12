package app.clearsms.data.repository

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** The Alerts age policy: active vs "Older alerts" bucketing. */
class ReminderBucketingTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 8, 12)
    private val nowMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun daysAgo(days: Long): Long = nowMs - TimeUnit.DAYS.toMillis(days)

    private fun undatedDelivery(
        id: Long,
        createdAt: Long,
        reference: String = "AWB-$id",
    ) = ReminderEntity(
        id = id,
        type = ReminderType.DELIVERY,
        dueDate = null,
        bankName = "Blue Dart",
        label = reference,
        rawSmsId = id * 10,
        createdAt = createdAt,
    )

    private fun bill(
        id: Long,
        dueDate: Long?,
        createdAt: Long,
        dismissedAt: Long? = null,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.CREDIT_CARD,
        dueDate = dueDate,
        totalDue = 100.0,
        accountLast4 = "123$id",
        bankName = "IDFC FIRST",
        rawSmsId = id * 10,
        createdAt = createdAt,
        dismissedAt = dismissedAt,
    )

    @Test
    fun `undated delivery is active at 13 days and older at 15 days`() {
        val fresh = undatedDelivery(1, createdAt = daysAgo(13))
        val stale = undatedDelivery(2, createdAt = daysAgo(15))

        val buckets = ReminderBucketing.bucket(listOf(fresh, stale), nowMs)

        assertThat(buckets.active.map { it.id }).containsExactly(1L)
        assertThat(buckets.older.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `two year old dispatch imported today lands straight in older`() {
        // The user's report: a catch-up import surfaced years-old dispatch
        // messages as undated "upcoming" deliveries. createdAt is the
        // MESSAGE date, so the age policy buries them on arrival.
        val ancient = undatedDelivery(1, createdAt = daysAgo(730))

        val buckets = ReminderBucketing.bucket(listOf(ancient), nowMs)

        assertThat(buckets.active).isEmpty()
        assertThat(buckets.older.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `dated entries expire past their date regardless of message age`() {
        val dueTomorrow = bill(1, dueDate = nowMs + TimeUnit.DAYS.toMillis(1), createdAt = daysAgo(60))
        val dueToday = bill(2, dueDate = nowMs, createdAt = daysAgo(2))
        val overdue = bill(3, dueDate = daysAgo(1), createdAt = daysAgo(2))

        val buckets = ReminderBucketing.bucket(listOf(dueTomorrow, dueToday, overdue), nowMs)

        // An old message with a future due date stays active; due today is
        // still active; past the date it moves to Older.
        assertThat(buckets.active.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(buckets.older.map { it.id }).containsExactly(3L)
    }

    @Test
    fun `dateless bill is active at 29 days and older at 31 days`() {
        val fresh = bill(1, dueDate = null, createdAt = daysAgo(29))
        val stale = bill(2, dueDate = null, createdAt = daysAgo(31))

        val buckets = ReminderBucketing.bucket(listOf(fresh, stale), nowMs)

        assertThat(buckets.active.map { it.id }).containsExactly(1L)
        assertThat(buckets.older.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `dismissed reminder is older even with a future due date`() {
        val dismissed = bill(1, dueDate = nowMs + TimeUnit.DAYS.toMillis(10), createdAt = daysAgo(1), dismissedAt = daysAgo(0))

        val buckets = ReminderBucketing.bucket(listOf(dismissed), nowMs)

        assertThat(buckets.active).isEmpty()
        assertThat(buckets.older.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `re-parsed duplicate of a dismissed bill stays dismissed after merge`() {
        val futureDue = nowMs + TimeUnit.DAYS.toMillis(5)
        val dismissed = bill(1, dueDate = futureDue, createdAt = daysAgo(3), dismissedAt = daysAgo(1))
        // Same card, same due day, NEWER row without the flag (a re-parse).
        val reParsed = bill(2, dueDate = futureDue, createdAt = daysAgo(2)).copy(accountLast4 = "1231")

        val buckets = ReminderBucketing.bucket(listOf(dismissed, reParsed), nowMs)

        assertThat(buckets.active).isEmpty()
        assertThat(buckets.older).hasSize(1)
        assertThat(buckets.older.single().dismissedAt).isEqualTo(daysAgo(1))
    }

    @Test
    fun `older section sorts newest first by dismissal then date`() {
        val dismissedRecently = bill(1, dueDate = null, createdAt = daysAgo(40), dismissedAt = nowMs)
        val expiredLongAgo = bill(2, dueDate = daysAgo(20), createdAt = daysAgo(50))
        val expiredYesterday = bill(3, dueDate = daysAgo(1), createdAt = daysAgo(30))

        val buckets = ReminderBucketing.bucket(listOf(expiredLongAgo, dismissedRecently, expiredYesterday), nowMs)

        assertThat(buckets.older.map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
    }

    @Test
    fun `active list sorts by due date with undated entries last`() {
        val undated = undatedDelivery(1, createdAt = daysAgo(2))
        val dueSoon = bill(2, dueDate = nowMs + TimeUnit.DAYS.toMillis(1), createdAt = daysAgo(1))
        val dueLater = bill(3, dueDate = nowMs + TimeUnit.DAYS.toMillis(9), createdAt = daysAgo(1))

        val buckets = ReminderBucketing.bucket(listOf(undated, dueLater, dueSoon), nowMs)

        assertThat(buckets.active.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }
}
