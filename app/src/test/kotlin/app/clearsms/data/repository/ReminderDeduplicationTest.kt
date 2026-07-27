package app.clearsms.data.repository

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReminderDeduplicationTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun dayMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun reminder(
        id: Long,
        last4: String? = "1234",
        bank: String? = "IDFC FIRST",
        type: ReminderType = ReminderType.CREDIT_CARD,
        due: LocalDate? = LocalDate.of(2026, 8, 5),
        totalDue: Double? = null,
        minDue: Double? = null,
        createdAt: Long,
        rawSmsId: Long = id * 100,
    ) = ReminderEntity(
        id = id,
        type = type,
        dueDate = due?.let(::dayMs),
        totalDue = totalDue,
        minDue = minDue,
        accountLast4 = last4,
        bankName = bank,
        rawSmsId = rawSmsId,
        createdAt = createdAt,
    )

    @Test
    fun `two sms for the same card and due date collapse to one, newest kept`() {
        val older = reminder(id = 1, totalDue = 12_000.0, createdAt = 1_000)
        val newer = reminder(id = 2, totalDue = 13_500.0, createdAt = 2_000)

        val result = ReminderDeduplication.dedupe(listOf(older, newer), zone)

        assertThat(result).hasSize(1)
        assertThat(result.single().id).isEqualTo(2)
        assertThat(result.single().totalDue).isEqualTo(13_500.0)
    }

    @Test
    fun `kept reminder carries the newest source message id for click-through`() {
        val older = reminder(id = 1, createdAt = 1_000, rawSmsId = 11)
        val newer = reminder(id = 2, createdAt = 2_000, rawSmsId = 22)

        val result = ReminderDeduplication.dedupe(listOf(older, newer), zone)

        assertThat(result.single().rawSmsId).isEqualTo(22)
    }

    @Test
    fun `newest null fields fall back to the most recent non-null value`() {
        val oldest = reminder(id = 1, totalDue = 10_000.0, minDue = 500.0, createdAt = 1_000)
        val middle = reminder(id = 2, totalDue = 11_000.0, minDue = null, createdAt = 2_000)
        val newest = reminder(id = 3, totalDue = null, minDue = null, createdAt = 3_000)

        val merged = ReminderDeduplication.dedupe(listOf(oldest, middle, newest), zone).single()

        assertThat(merged.id).isEqualTo(3)
        // Newest lacks totalDue → most recent non-null (middle) wins over oldest.
        assertThat(merged.totalDue).isEqualTo(11_000.0)
        // Neither newest nor middle has minDue → falls back to the oldest.
        assertThat(merged.minDue).isEqualTo(500.0)
    }

    @Test
    fun `different cards stay separate`() {
        val cardA = reminder(id = 1, last4 = "1234", createdAt = 1_000)
        val cardB = reminder(id = 2, last4 = "9876", createdAt = 2_000)

        assertThat(ReminderDeduplication.dedupe(listOf(cardA, cardB), zone)).hasSize(2)
    }

    @Test
    fun `different due months stay separate`() {
        val july = reminder(id = 1, due = LocalDate.of(2026, 7, 5), createdAt = 1_000)
        val august = reminder(id = 2, due = LocalDate.of(2026, 8, 5), createdAt = 2_000)

        assertThat(ReminderDeduplication.dedupe(listOf(july, august), zone)).hasSize(2)
    }

    @Test
    fun `different reminder types stay separate`() {
        val bill = reminder(id = 1, type = ReminderType.CREDIT_CARD, createdAt = 1_000)
        val emi = reminder(id = 2, type = ReminderType.EMI, createdAt = 2_000)

        assertThat(ReminderDeduplication.dedupe(listOf(bill, emi), zone)).hasSize(2)
    }

    @Test
    fun `reminders without last4 group by bank name`() {
        val first = reminder(id = 1, last4 = null, bank = "ACME POWER", createdAt = 1_000)
        val second = reminder(id = 2, last4 = null, bank = "ACME POWER", createdAt = 2_000)
        val other = reminder(id = 3, last4 = null, bank = "CITY WATER", createdAt = 3_000)

        val result = ReminderDeduplication.dedupe(listOf(first, second, other), zone)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly(2L, 3L)
    }

    @Test
    fun `reminders with no identity are never merged`() {
        val a = reminder(id = 1, last4 = null, bank = null, createdAt = 1_000)
        val b = reminder(id = 2, last4 = null, bank = null, createdAt = 2_000)

        assertThat(ReminderDeduplication.dedupe(listOf(a, b), zone)).hasSize(2)
    }
}
