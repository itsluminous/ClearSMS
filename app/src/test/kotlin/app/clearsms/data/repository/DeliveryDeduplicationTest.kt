package app.clearsms.data.repository

import app.clearsms.data.db.ReminderEntity
import app.clearsms.domain.model.ReminderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DeliveryDeduplicationTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun dayMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun delivery(
        id: Long,
        reference: String? = "OD1234567890",
        merchant: String? = "Flipkart",
        due: LocalDate = LocalDate.of(2026, 7, 25),
        createdAt: Long,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.DELIVERY,
        dueDate = dayMs(due),
        bankName = merchant,
        label = reference,
        rawSmsId = id * 100,
        createdAt = createdAt,
    )

    @Test
    fun `same tracking reference and date collapse to one card`() {
        val first = delivery(id = 1, createdAt = 1_000)
        val second = delivery(id = 2, createdAt = 2_000)

        val result = ReminderDeduplication.dedupe(listOf(first, second), zone)

        assertThat(result).hasSize(1)
        assertThat(result.single().id).isEqualTo(2)
    }

    @Test
    fun `a later sms with a revised date replaces the old delivery card`() {
        val original = delivery(id = 1, due = LocalDate.of(2026, 7, 25), createdAt = 1_000)
        val delayed = delivery(id = 2, due = LocalDate.of(2026, 7, 27), createdAt = 2_000)

        val result = ReminderDeduplication.dedupe(listOf(original, delayed), zone)

        assertThat(result).hasSize(1)
        assertThat(result.single().dueDate).isEqualTo(dayMs(LocalDate.of(2026, 7, 27)))
        assertThat(result.single().rawSmsId).isEqualTo(200)
    }

    @Test
    fun `different tracking references stay separate`() {
        val one = delivery(id = 1, reference = "OD1111111111", createdAt = 1_000)
        val two = delivery(id = 2, reference = "OD2222222222", createdAt = 2_000)

        assertThat(ReminderDeduplication.dedupe(listOf(one, two), zone)).hasSize(2)
    }

    @Test
    fun `without a reference the merchant and expected day group deliveries`() {
        val morning = delivery(id = 1, reference = null, createdAt = 1_000)
        val evening = delivery(id = 2, reference = null, createdAt = 2_000)
        val otherDay = delivery(id = 3, reference = null, due = LocalDate.of(2026, 7, 26), createdAt = 3_000)

        val result = ReminderDeduplication.dedupe(listOf(morning, evening, otherDay), zone)

        assertThat(result).hasSize(2)
    }

    @Test
    fun `deliveries never merge with bill reminders`() {
        val bill =
            ReminderEntity(
                id = 1,
                type = ReminderType.CREDIT_CARD,
                dueDate = dayMs(LocalDate.of(2026, 7, 25)),
                accountLast4 = null,
                bankName = "Flipkart",
                rawSmsId = 100,
                createdAt = 1_000,
            )
        val parcel = delivery(id = 2, reference = null, createdAt = 2_000)

        assertThat(ReminderDeduplication.dedupe(listOf(bill, parcel), zone)).hasSize(2)
    }
}
